package com.sportenth.data.database.entity

import org.jetbrains.exposed.sql.Table

object OrienteeringResults : Table("orienteering_results") {
    val id = varchar("id", 200)
    val competitionId = long("competition_id")
    val groupId = varchar("group_id", 200)
    val participantId = varchar("participant_id", 200)
    val startTime = long("start_time").nullable()
    val finishTime = long("finish_time").nullable()
    val totalTime = long("total_time").nullable()
    val rank = integer("rank").nullable()
    val status = varchar("status", 50)
    val penaltyTime = long("penalty_time")
    val isEditable = bool("is_editable")
    val isEdited = bool("is_edited")

    override val primaryKey = PrimaryKey(id)
}
