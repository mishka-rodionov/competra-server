package com.competra.data.database.entity

import org.jetbrains.exposed.v1.core.Table

/**
 * Расширение ядра [Competitions] специфичными для ориентирования полями.
 *
 * Идентичность 1:1: [id] совпадает с [Competitions.id] (один и тот же клиентский UUID),
 * одновременно является первичным ключом и внешним ключом на ядро (CASCADE при удалении).
 * Отдельной колонки competition_id больше нет, владелец (ownerId) переехал в [Competitions].
 */
object OrienteeringCompetitions : Table("orienteering_competitions") {
    val id = varchar("id", 36).references(Competitions.id, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE)
    val direction = varchar("direction", 100)
    val punchingSystem = varchar("punching_system", 100)
    val startTimeMode = varchar("start_time_mode", 100)
    val countdownTimer = long("countdown_timer").nullable()
    val startTime = long("start_time").nullable()
    val startIntervalSeconds = integer("start_interval_seconds").nullable()

    /**
     * Контрольное время соревнования в минутах (null — не задано).
     * Группа может переопределить его через [ParticipantGroups.timeLimitMinutes].
     */
    val controlTimeMinutes = integer("control_time_minutes").nullable()

    /** [com.competra.domain.orienteering.OvertimePolicy] — что делать с превысившими КВ. */
    val overtimePolicy = varchar("overtime_policy", 20).default("IGNORE")

    /** [com.competra.domain.orienteering.ByChoiceMode] — итог формата «по выбору»: по баллам или по минимуму КП. */
    val byChoiceMode = varchar("by_choice_mode", 20).default("SCORE")

    /** Настройки командного зачёта JSON-ом ([com.competra.domain.orienteering.TeamScoring]); null — зачёта нет. */
    val teamScoring = text("team_scoring").nullable()

    /** [com.competra.domain.orienteering.DrawMode] проведённой жеребьёвки; null — не проводилась. */
    val drawMode = varchar("draw_mode", 20).nullable()

    /** Число коридоров жеребьёвки по дистанциям (только для DISTANCE). */
    val drawCorridors = integer("draw_corridors").nullable()

    /** Минимальный зазор между стартами одной дистанции в интервалах (только для DISTANCE). */
    val drawGap = integer("draw_gap").nullable()

    val updatedAt = long("updated_at").default(0L)

    override val primaryKey = PrimaryKey(id)
}
