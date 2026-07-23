package com.competra.data.services

import com.competra.data.database.entity.CompetitionNotificationType
import com.competra.data.database.entity.CompetitionNotifications
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * Учёт того, какие push-уведомления по соревнованиям уже отправлены — см. [CompetitionNotifications].
 */
class CompetitionNotificationLogService {

    suspend fun hasSent(competitionId: String, type: CompetitionNotificationType): Boolean =
        newSuspendedTransaction(Dispatchers.IO) {
            CompetitionNotifications.selectAll()
                .where {
                    (CompetitionNotifications.competitionId eq competitionId) and
                        (CompetitionNotifications.notificationType eq type.name)
                }
                .empty().not()
        }

    /** Идемпотентно: повторный вызов для уже отмеченной пары не приводит к ошибке. */
    suspend fun markSent(competitionId: String, type: CompetitionNotificationType) {
        newSuspendedTransaction(Dispatchers.IO) {
            CompetitionNotifications.insertIgnore {
                it[CompetitionNotifications.competitionId] = competitionId
                it[notificationType] = type.name
                it[sentAt] = System.currentTimeMillis()
            }
        }
    }

    /** Из [candidateIds] возвращает те, для которых уведомление [type] ещё не отправлялось. */
    suspend fun unsentCompetitionIds(type: CompetitionNotificationType, candidateIds: List<String>): List<String> {
        if (candidateIds.isEmpty()) return emptyList()
        val alreadySent = newSuspendedTransaction(Dispatchers.IO) {
            CompetitionNotifications.selectAll()
                .where {
                    (CompetitionNotifications.notificationType eq type.name) and
                        (CompetitionNotifications.competitionId inList candidateIds)
                }
                .map { it[CompetitionNotifications.competitionId] }
                .toSet()
        }
        return candidateIds.filterNot { it in alreadySent }
    }
}
