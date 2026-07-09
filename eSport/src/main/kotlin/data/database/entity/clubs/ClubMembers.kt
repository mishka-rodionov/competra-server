package com.competra.data.database.entity.clubs

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/** Членство зарегистрированного пользователя в клубе. Гостевые/анонимные участники не допускаются. */
object ClubMembers : Table("club_members") {
    val id = varchar("id", 36)
    val clubId = varchar("club_id", 36).references(Clubs.id, onDelete = ReferenceOption.CASCADE)
    val userId = varchar("user_id", 200)
    /** FOUNDER | ADMIN | MEMBER */
    val role = varchar("role", 20)
    val joinedAt = long("joined_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("club_members_club_user_idx", clubId, userId)
    }
}
