package com.sportenth.data.database.entity

import org.jetbrains.exposed.sql.Table

object Distances : Table("distances") {
    val id = long("id").autoIncrement()
    val competitionId = long("competition_id")
    val name = varchar("name", 200).nullable()
    val lengthMeters = integer("length_meters")
    val climbMeters = integer("climb_meters")
    val controlsCount = integer("controls_count")
    val description = varchar("description", 1000).nullable()

    override val primaryKey = PrimaryKey(id)
}
