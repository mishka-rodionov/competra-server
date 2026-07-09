package com.competra.data.database.entity.clubs

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/** Членство участника клуба (ClubMember) в конкретной команде. Роль капитана — на уровне команды, не клуба. */
object TeamMembers : Table("team_members") {
    val id = varchar("id", 36)
    val teamId = varchar("team_id", 36).references(Teams.id, onDelete = ReferenceOption.CASCADE)
    val clubMemberId = varchar("club_member_id", 36).references(ClubMembers.id, onDelete = ReferenceOption.CASCADE)
    /** CAPTAIN | MEMBER */
    val role = varchar("role", 20)
    val joinedAt = long("joined_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("team_members_team_club_member_idx", teamId, clubMemberId)
    }
}
