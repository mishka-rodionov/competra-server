package com.competra.data.database.entity

import org.jetbrains.exposed.v1.core.Table

object OrienteeringParticipants : Table("orienteering_participants") {
    val id = varchar("id", 200)
    val userId = varchar("user_id", 200).nullable()
    val firstName = varchar("first_name", 200)
    val lastName = varchar("last_name", 200)
    val groupId = long("group_id")
    val groupName = varchar("group_name", 200)
    val competitionId = varchar("competition_id", 36)
        .references(Competitions.id, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE)
    val commandName = varchar("command_name", 200).nullable()
    /**
     * Клубная команда, выбранная при самостоятельной регистрации. [commandName] при этом остаётся
     * подписью для протокола. FK на teams (ON DELETE SET NULL) добавляется миграцией в Databases.kt.
     */
    val teamId = varchar("team_id", 36).nullable()
    val startNumber = integer("start_number")
    val startTime = long("start_time")
    val chipNumber = long("chip_number")
    val comment = varchar("comment", 500).nullable()
    val isChipGiven = bool("is_chip_given")
    val updatedAt = long("updated_at").default(0L)

    override val primaryKey = PrimaryKey(id)
}
