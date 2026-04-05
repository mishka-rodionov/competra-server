package com.sportenth.data.database.entity

import org.jetbrains.exposed.sql.Table

object OrienteeringParticipants : Table("orienteering_participants") {
    val id = varchar("id", 200)
    val userId = varchar("user_id", 200).nullable()
    val firstName = varchar("first_name", 200)
    val lastName = varchar("last_name", 200)
    val groupId = varchar("group_id", 200)
    val groupName = varchar("group_name", 200)
    val competitionId = varchar("competition_id", 200)
    val commandName = varchar("command_name", 200).nullable()
    val startNumber = integer("start_number")
    val startTime = long("start_time")
    val chipNumber = long("chip_number")
    val comment = varchar("comment", 500).nullable()
    val isChipGiven = bool("is_chip_given")

    override val primaryKey = PrimaryKey(id)
}
