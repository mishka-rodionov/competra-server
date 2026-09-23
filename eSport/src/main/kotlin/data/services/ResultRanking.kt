package com.competra.data.services

import com.competra.data.database.entity.OrienteeringCompetitions
import com.competra.data.database.entity.OrienteeringResults
import com.competra.data.database.entity.ParticipantGroups
import com.competra.domain.orienteering.OvertimePolicy
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

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
     * Для направления BY_CHOICE (score-О) места считаются по сумме баллов (убывание),
     * тай-брейк — по времени прохождения дистанции (totalTime = finish - start участника).
     * Для остальных направлений — по общему времени с учётом штрафа (возрастание).
     *
     * Тай-брейк BY_CHOICE использует именно totalTime, а не finishTime (абсолютное время по
     * часам) — при интервальном/разном старте участников более раннее абсолютное время финиша
     * не означает более быстрый забег. См. аналогичную логику и комментарий в Android-клиенте:
     * OrienteeringCompetitionInteractor.recalculateRanksV2.
     */
    fun recalculateGroup(competitionId: String, groupId: Long) {
        val orient = OrienteeringCompetitions.selectAll()
            .where { OrienteeringCompetitions.id eq competitionId }
            .singleOrNull()

        val direction = orient?.get(OrienteeringCompetitions.direction)
        val policy = OvertimePolicy.fromString(orient?.get(OrienteeringCompetitions.overtimePolicy))

        val groupLimitMinutes = ParticipantGroups.selectAll()
            .where { ParticipantGroups.id eq groupId }
            .singleOrNull()
            ?.get(ParticipantGroups.timeLimitMinutes)

        val limitMillis = effectiveControlTimeMinutes(
            groupLimitMinutes = groupLimitMinutes,
            competitionLimitMinutes = orient?.get(OrienteeringCompetitions.controlTimeMinutes)
        )?.let { it * 60_000L }

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
        val disqualifiesOvertime = policy == OvertimePolicy.DISQUALIFY && limitMillis != null
        val (overtimeRows, finishedRows) = rows.partition {
            disqualifiesOvertime && isOvertime(it[OrienteeringResults.totalTime], limitMillis)
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

        val comparator: Comparator<ResultRow> = if (direction == "BY_CHOICE") {
            compareByDescending<ResultRow> { it[OrienteeringResults.totalScore] ?: 0 }
                .thenBy { it[OrienteeringResults.totalTime] ?: Long.MAX_VALUE }
        } else {
            compareBy { (it[OrienteeringResults.totalTime] ?: Long.MAX_VALUE) + it[OrienteeringResults.penaltyTime] }
        }

        val sortedRows = finishedRows.sortedWith(comparator)

        // Для BY_CHOICE ключ должен включать totalTime — иначе два участника с одинаковыми
        // очками, но разным временем (тай-брейк уже учтён компаратором выше), получат одно и то
        // же место вместо разных.
        fun rankKey(row: ResultRow): Any = if (direction == "BY_CHOICE") {
            (row[OrienteeringResults.totalScore] ?: 0) to (row[OrienteeringResults.totalTime] ?: Long.MAX_VALUE)
        } else {
            (row[OrienteeringResults.totalTime] ?: Long.MAX_VALUE) + row[OrienteeringResults.penaltyTime]
        }

        var rank = 1
        var prevKey: Any? = null
        var skipCount = 0

        sortedRows.forEachIndexed { index, row ->
            val key = rankKey(row)

            if (prevKey != null && key == prevKey) {
                skipCount++
            } else {
                rank = index + 1 - skipCount
                prevKey = key
            }

            OrienteeringResults.update({ OrienteeringResults.id eq row[OrienteeringResults.id] }) {
                it[OrienteeringResults.rank] = rank
            }
        }
    }

    /**
     * Превышение КВ считается по чистому времени на дистанции (финиш − старт участника),
     * без штрафного времени: [OrienteeringResults.penaltyTime] — санкция судьи, а не бег.
     * Ровно КВ укладывается в лимит, снимается только строгое превышение.
     */
    fun isOvertime(totalTime: Long?, limitMillis: Long?): Boolean =
        limitMillis != null && totalTime != null && totalTime > limitMillis
}
