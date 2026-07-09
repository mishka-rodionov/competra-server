package com.competra.data.database.entity.clubs

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

object ClubJoinRequests : Table("club_join_requests") {
    val id = varchar("id", 36)
    val clubId = varchar("club_id", 36).references(Clubs.id, onDelete = ReferenceOption.CASCADE)
    val userId = varchar("user_id", 200)
    /** PENDING | APPROVED | REJECTED */
    val status = varchar("status", 20).default("PENDING")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at").default(0L)

    override val primaryKey = PrimaryKey(id)
}
