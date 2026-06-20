package com.competra.data.database.entity

import org.jetbrains.exposed.sql.Table

/**
 * Расширение ядра [Competitions] специфичными для ориентирования полями.
 *
 * Идентичность 1:1: [id] совпадает с [Competitions.id] (один и тот же клиентский UUID),
 * одновременно является первичным ключом и внешним ключом на ядро (CASCADE при удалении).
 * Отдельной колонки competition_id больше нет, владелец (ownerId) переехал в [Competitions].
 */
object OrienteeringCompetitions : Table("orienteering_competitions") {
    val id = varchar("id", 36).references(Competitions.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val direction = varchar("direction", 100)
    val punchingSystem = varchar("punching_system", 100)
    val startTimeMode = varchar("start_time_mode", 100)
    val countdownTimer = long("countdown_timer").nullable()
    val startTime = long("start_time").nullable()
    val startIntervalSeconds = integer("start_interval_seconds").nullable()
    val updatedAt = long("updated_at").default(0L)

    override val primaryKey = PrimaryKey(id)
}
