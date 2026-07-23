package com.competra.data.database.entity

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Категория автоматического push-уведомления, привязанного к соревнованию.
 * Значение хранится как строка в [CompetitionNotifications.notificationType].
 */
enum class CompetitionNotificationType {
    START_REMINDER,
    DAY_BEFORE_REMINDER,
    RESULTS_PUBLISHED,
}

/**
 * Лог отправленных push-уведомлений по соревнованиям. Наличие строки (competitionId, notificationType)
 * означает, что уведомление этого типа для этого соревнования уже отправлено — единый механизм
 * дедупликации для всех категорий вместо отдельного булевского поля на каждую в [Competitions].
 */
object CompetitionNotifications : Table("competition_notifications") {
    val competitionId = varchar("competition_id", 36)
        .references(Competitions.id, onDelete = ReferenceOption.CASCADE)
    val notificationType = varchar("notification_type", 50)
    val sentAt = long("sent_at")

    override val primaryKey = PrimaryKey(competitionId, notificationType)
}
