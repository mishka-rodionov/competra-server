package com.competra.data.database.entity

import org.jetbrains.exposed.sql.Table

object ParticipantGroups : Table("participant_groups") {
    val id = long("id").autoIncrement()
    val competitionId = varchar("competition_id", 36)
        .references(Competitions.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val title = varchar("title", 200)
    val gender = varchar("gender", 20).nullable()
    val minAge = integer("min_age").nullable()
    val maxAge = integer("max_age").nullable()
    val distanceId = long("distance_id")
    val maxParticipants = integer("max_participants").nullable()
    val updatedAt = long("updated_at").default(0L)

    override val primaryKey = PrimaryKey(id)
}
