package com.competra.data.database.entity.rating

import com.competra.data.database.entity.clubs.Clubs
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/** Рейтинг соревнований, принадлежащий клубу. Удаляется вместе с клубом-владельцем. */
object RatingSeries : Table("rating_series") {
    val id = varchar("id", 36)
    val name = varchar("name", 200)
    val ownerClubId = varchar("owner_club_id", 36).references(Clubs.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at").default(0L)

    override val primaryKey = PrimaryKey(id)
}
