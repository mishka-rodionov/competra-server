package com.competra.data.database.entity.clubs

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

object Teams : Table("teams") {
    val id = varchar("id", 36)
    val clubId = varchar("club_id", 36).references(Clubs.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 200)
    /** Значения из домена KindOfSport: Orienteering | CrossCountrySki | TrailRunning. */
    val sportType = varchar("sport_type", 50)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at").default(0L)

    override val primaryKey = PrimaryKey(id)
}
