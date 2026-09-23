package com.competra.tracking

import com.competra.data.database.entity.Competitions
import com.competra.data.database.entity.Distances
import com.competra.data.database.entity.OrienteeringCompetitions
import com.competra.data.database.entity.OrienteeringParticipants
import com.competra.data.database.entity.OrienteeringResults
import com.competra.data.database.entity.ParticipantGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Статусы результата, после которых участник считается закончившим дистанцию. */
val FINAL_RESULT_STATUSES = setOf("FINISHED", "DSQ", "DNS", "DNF", "OVERTIME")

/**
 * Всё, что трекингу нужно знать об участнике на старте сессии.
 *
 * @property userId Пользователь, которому принадлежит участник, или `null` (гость без аккаунта).
 * @property distanceId Дистанция его группы или `null`, если группе не назначена дистанция.
 * @property plannedStartTime Плановое время старта (Unix ms) или `null`, если не назначено.
 * @property competitionStartDate Начало соревнования (Unix ms).
 * @property competitionEndDate Конец соревнования (Unix ms) или `null` (однодневное).
 * @property competitionStatus Сохранённый статус соревнования (`IN_PROGRESS`, `FINISHED`, …).
 * @property controlTimeMinutes Контрольное время: группы, иначе соревнования; `null` — не задано.
 * @property hasFinalResult Есть ли у участника финальный результат ([FINAL_RESULT_STATUSES]).
 */
data class ParticipantContext(
    val participantId: String,
    val userId: String?,
    val competitionId: String,
    val displayName: String,
    val groupName: String?,
    val startNumber: Int?,
    val distanceId: Long?,
    val distanceName: String?,
    val plannedStartTime: Long?,
    val competitionStartDate: Long,
    val competitionEndDate: Long?,
    val competitionStatus: String,
    val controlTimeMinutes: Int?,
    val hasFinalResult: Boolean
)

/** Источник данных об участниках (основная БД). Интерфейс — ради тестов без БД. */
interface ParticipantDirectory {
    /** Участник со всем контекстом или `null`, если его нет. */
    suspend fun find(participantId: String): ParticipantContext?
}

/**
 * Читает участника из основной БД через пул только на чтение (роль `competra_tracking_ro`,
 * `statement_timeout` 3 с). Вызывается один раз на старт сессии.
 */
class MainDbParticipantDirectory(private val mainReadOnly: Database) : ParticipantDirectory {

    override suspend fun find(participantId: String): ParticipantContext? = withContext(Dispatchers.IO) {
        transaction(mainReadOnly) {
            val participant = OrienteeringParticipants.selectAll()
                .where { OrienteeringParticipants.id eq participantId }
                .singleOrNull() ?: return@transaction null
            val competitionId = participant[OrienteeringParticipants.competitionId]
            val competition = Competitions.selectAll()
                .where { Competitions.id eq competitionId }
                .singleOrNull() ?: return@transaction null
            val group = ParticipantGroups.selectAll()
                .where { ParticipantGroups.id eq participant[OrienteeringParticipants.groupId] }
                .singleOrNull()
            val distanceId = group?.get(ParticipantGroups.distanceId)?.takeIf { it > 0 }
            val distanceName = distanceId?.let { id ->
                Distances.selectAll().where { Distances.id eq id }.singleOrNull()?.get(Distances.name)
            }
            val competitionControlTime = OrienteeringCompetitions.selectAll()
                .where { OrienteeringCompetitions.id eq competitionId }
                .singleOrNull()?.get(OrienteeringCompetitions.controlTimeMinutes)
            val hasFinalResult = !OrienteeringResults.selectAll()
                .where {
                    (OrienteeringResults.participantId eq participantId) and
                        (OrienteeringResults.status inList FINAL_RESULT_STATUSES)
                }
                .empty()

            ParticipantContext(
                participantId = participantId,
                userId = participant[OrienteeringParticipants.userId],
                competitionId = competitionId,
                displayName = listOf(participant[OrienteeringParticipants.lastName], participant[OrienteeringParticipants.firstName])
                    .filter { it.isNotBlank() }
                    .joinToString(" "),
                groupName = group?.get(ParticipantGroups.title) ?: participant[OrienteeringParticipants.groupName],
                startNumber = participant[OrienteeringParticipants.startNumber].takeIf { it > 0 },
                distanceId = distanceId,
                distanceName = distanceName,
                plannedStartTime = participant[OrienteeringParticipants.startTime].takeIf { it > 0 },
                competitionStartDate = competition[Competitions.startDate],
                competitionEndDate = competition[Competitions.endDate],
                competitionStatus = competition[Competitions.status],
                controlTimeMinutes = group?.get(ParticipantGroups.timeLimitMinutes) ?: competitionControlTime,
                hasFinalResult = hasFinalResult
            )
        }
    }
}
