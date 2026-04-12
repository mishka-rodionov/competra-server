package com.sportenth.data.database.entity

import org.jetbrains.exposed.sql.Table

object ParticipantGroups : Table("participant_groups") {
    val id = varchar("id", 200)
    val competitionId = long("competition_id")
    val title = varchar("title", 200)
    val gender = varchar("gender", 20).nullable()
    val minAge = integer("min_age").nullable()
    val maxAge = integer("max_age").nullable()
    val distanceId = varchar("distance_id", 200)
    val maxParticipants = integer("max_participants").nullable()

    override val primaryKey = PrimaryKey(id)
}
