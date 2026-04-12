package com.sportenth.data.database.entity

import org.jetbrains.exposed.sql.Table

object OrienteeringCompetitions : Table("orienteering_competitions") {
    val id = varchar("id", 200)
    val competitionId = long("competition_id")
    val userId = varchar("user_id", 200)
    val direction = varchar("direction", 100)
    val punchingSystem = varchar("punching_system", 100)
    val startTimeMode = varchar("start_time_mode", 100)
    val countdownTimer = long("countdown_timer").nullable()
    val startTime = long("start_time").nullable()
    val startIntervalSeconds = integer("start_interval_seconds").nullable()

    override val primaryKey = PrimaryKey(id)
}
