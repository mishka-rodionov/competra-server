package com.competra.data.services

import com.competra.data.database.entity.OrienteeringCompetitions
import com.competra.data.database.entity.OrienteeringResults
import com.competra.data.database.entity.ParticipantGroups
import com.competra.data.database.entity.OrienteeringParticipants
import com.competra.data.database.entity.Distances
import com.competra.domain.orienteering.OvertimePolicies
import com.competra.domain.orienteering.OvertimePolicy
import com.competra.domain.orienteering.effectiveCourseFormat
import com.competra.domain.orienteering.competitionRanks
import com.competra.domain.orienteering.ranksByScore
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.core.inList

/**
 * Пересчёт мест в группе и применение политики контрольного времени (КВ).
 *
 * Вынесено из [OrienteeringResultService] отдельным объектом, потому что пересчёт запускают три
 * источника: сохранение результата, изменение настроек КВ соревнования
 * ([OrienteeringCompetitionService]) и изменение КВ группы ([ParticipantGroupService]).
 *
 * Все функции вызываются внутри уже открытой транзакции.
 *
 * Правило КВ применяется гибридно: Android считает его локально при считывании чипа (офлайн),
 * сервер — здесь, при каждом пересчёте. Статус выводится заново из времени и текущих настроек,
 * поэтому снятие обратимо: смена политики на IGNORE или увеличение КВ возвращают результат
 * в FINISHED без ручного вмешательства.
 */
internal object ResultRanking {

    const val STATUS_FINISHED = "FINISHED"
    const val STATUS_OVERTIME = "OVERTIME"

    /** Итоговое КВ группы в минутах: своё значение группы, иначе умолчание соревнования. */
    fun effectiveControlTimeMinutes(groupLimitMinutes: Int?, competitionLimitMinutes: Int?): Int? =
        groupLimitMinutes ?: competitionLimitMinutes

    /** Пересчитывает все группы соревнования — после изменения КВ или политики соревнования. */
    fun recalculateCompetition(competitionId: String) {
        OrienteeringResults.selectAll()
            .where { OrienteeringResults.competitionId eq competitionId }
            .map { it[OrienteeringResults.groupId] }
            .distinct()
            .forEach { groupId -> recalculateGroup(competitionId, groupId) }
    }

    /**
     * Пересчитывает статусы КВ и места для результатов группы.
     *
     * Формат берётся у дистанции группы (см. effectiveCourseFormat), политика КВ — по виду
     * формата (OvertimePolicies.forCourse).
     *
     * Для score-О (BY_CHOICE + ByChoiceMode.SCORE) места считаются по сумме баллов (убывание),
     * тай-брейк — по времени прохождения дистанции (totalTime = finish - start участника).
     * Для остальных направлений, в т.ч. «по выбору» с минимумом КП (ByChoiceMode.MIN_CONTROLS), —
     * по общему времени с учётом штрафа (возрастание).
     *
     * Тай-брейк BY_CHOICE использует именно totalTime, а не finishTime (абсолютное время по
     * часам) — при интервальном/разном старте участников более раннее абсолютное время финиша
     * не означает более быстрый забег. См. аналогичную логику и комментарий в Android-клиенте:
     * OrienteeringCompetitionInteractor.recalculateRanksV2.
     *
     * Участники вне конкурса ([OrienteeringParticipants.isOutOfCompetition]) места не получают
     * (rank = null) и места остальных не сдвигают; статус КВ к ним применяется как обычно.
     * Равные результаты делят место по [competitionRanks] (1, 1, 3).
     */
    fun recalculateGroup(competitionId: String, groupId: Long) {
        val orient = OrienteeringCompetitions.selectAll()
            .where { OrienteeringCompetitions.id eq competitionId }
            .singleOrNull()

        val group = ParticipantGroups.selectAll()
            .where { ParticipantGroups.id eq groupId }
            .singleOrNull()
        val groupLimitMinutes = group?.get(ParticipantGroups.timeLimitMinutes)

        // Формат — у дистанции группы, иначе соревнования (группа без дистанции — соревнования).
        val distance = group?.get(ParticipantGroups.distanceId)?.let { distanceId ->
            Distances.selectAll().where { Distances.id eq distanceId }.singleOrNull()
        }
        val competitionDirection = orient?.get(OrienteeringCompetitions.direction)
        val competitionByChoiceMode = orient?.get(OrienteeringCompetitions.byChoiceMode)
        val byScore = effectiveCourseFormat(
            distanceDirection = distance?.get(Distances.direction),
            distanceByChoiceMode = distance?.get(Distances.byChoiceMode),
            competitionDirection = competitionDirection,
            competitionByChoiceMode = competitionByChoiceMode
        ).ranksByScore
        val policy = OvertimePolicies.resolve(
            legacy = orient?.get(OrienteeringCompetitions.overtimePolicy),
            time = orient?.get(OrienteeringCompetitions.timeOvertimePolicy),
            score = orient?.get(OrienteeringCompetitions.scoreOvertimePolicy),
            competitionRanksByScore = ranksByScore(competitionDirection, competitionByChoiceMode)
        ).forCourse(byScore)

        // totalTime и penaltyTime хранятся в СЕКУНДАХ (см. OrienteeringResult в Android-клиенте),
        // поэтому лимит переводим в секунды, а не в миллисекунды.
        val limitSeconds = effectiveControlTimeMinutes(
            groupLimitMinutes = groupLimitMinutes,
            competitionLimitMinutes = orient?.get(OrienteeringCompetitions.controlTimeMinutes)
        )?.let { it * 60L }

        // Берём оба статуса: OVERTIME выводится заново и может вернуться в FINISHED.
        val rows = OrienteeringResults.selectAll()
            .where {
                (OrienteeringResults.competitionId eq competitionId) and
                (OrienteeringResults.groupId eq groupId) and
                (OrienteeringResults.status inList listOf(STATUS_FINISHED, STATUS_OVERTIME))
            }
            .toList()

        // Снимаем только при DISQUALIFY: при IGNORE КВ носит справочный характер, при
        // SCORE_PENALTY (BY_CHOICE) опоздание штрафуется очками, а не снятием.
        val disqualifiesOvertime = policy == OvertimePolicy.DISQUALIFY && limitSeconds != null
        // Ручной статус организатора («Засчитать») КВ не перетирает.
        val (overtimeRows, finishedRows) = rows.partition {
            disqualifiesOvertime && !it[OrienteeringResults.isStatusManual] &&
                isOvertime(it[OrienteeringResults.totalTime], limitSeconds)
        }

        // Статус пишем только при фактическом изменении, места — как раньше, всем FINISHED.
        // updatedAt при этом не трогаем: как и места, статус КВ — производная величина,
        // клиенты получают её при полной выгрузке результатов соревнования.
        overtimeRows.forEach { row ->
            if (row[OrienteeringResults.status] != STATUS_OVERTIME || row[OrienteeringResults.rank] != null) {
                OrienteeringResults.update({ OrienteeringResults.id eq row[OrienteeringResults.id] }) {
                    it[status] = STATUS_OVERTIME
                    it[rank] = null
                }
            }
        }

        finishedRows.forEach { row ->
            if (row[OrienteeringResults.status] != STATUS_FINISHED) {
                OrienteeringResults.update({ OrienteeringResults.id eq row[OrienteeringResults.id] }) {
                    it[status] = STATUS_FINISHED
                }
            }
        }

        val comparator: Comparator<ResultRow> = if (byScore) {
            compareByDescending<ResultRow> { it[OrienteeringResults.totalScore] ?: 0 }
                .thenBy { it[OrienteeringResults.totalTime] ?: Long.MAX_VALUE }
        } else {
            compareBy { (it[OrienteeringResults.totalTime] ?: Long.MAX_VALUE) + it[OrienteeringResults.penaltyTime] }
        }

        val outOfCompetitionIds = OrienteeringParticipants.selectAll()
            .where {
                (OrienteeringParticipants.competitionId eq competitionId) and
                    (OrienteeringParticipants.isOutOfCompetition eq true)
            }
            .map { it[OrienteeringParticipants.id] }
            .toSet()
        val (outOfCompetitionRows, rankedRows) = finishedRows.partition {
            it[OrienteeringResults.participantId] in outOfCompetitionIds
        }
        outOfCompetitionRows.forEach { row ->
            if (row[OrienteeringResults.rank] != null) {
                OrienteeringResults.update({ OrienteeringResults.id eq row[OrienteeringResults.id] }) {
                    it[rank] = null
                }
            }
        }

        val sortedRows = rankedRows.sortedWith(comparator)

        // Для score-О ключ должен включать totalTime — иначе два участника с одинаковыми
        // очками, но разным временем (тай-брейк уже учтён компаратором выше), получат одно и то
        // же место вместо разных.
        fun rankKey(row: ResultRow): Any = if (byScore) {
            (row[OrienteeringResults.totalScore] ?: 0) to (row[OrienteeringResults.totalTime] ?: Long.MAX_VALUE)
        } else {
            (row[OrienteeringResults.totalTime] ?: Long.MAX_VALUE) + row[OrienteeringResults.penaltyTime]
        }

        val ranks = competitionRanks(sortedRows.map(::rankKey))
        sortedRows.forEachIndexed { index, row ->
            OrienteeringResults.update({ OrienteeringResults.id eq row[OrienteeringResults.id] }) {
                it[OrienteeringResults.rank] = ranks[index]
            }
        }
    }

    /**
     * Превышение КВ считается по чистому времени на дистанции (финиш − старт участника),
     * без штрафного времени: [OrienteeringResults.penaltyTime] — санкция судьи, а не бег.
     * Ровно КВ укладывается в лимит, снимается только строгое превышение.
     *
     * Обе величины — в секундах.
     */
    fun isOvertime(totalTimeSeconds: Long?, limitSeconds: Long?): Boolean =
        limitSeconds != null && totalTimeSeconds != null && totalTimeSeconds > limitSeconds
}
