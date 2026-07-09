package com.competra.data.database.entity.clubs

import org.jetbrains.exposed.sql.Table

object Clubs : Table("clubs") {
    val id = varchar("id", 36)
    val name = varchar("name", 200)
    val description = varchar("description", 1000).nullable()
    /** Разрешён ли приём заявок на вступление извне. Клуб при этом всегда публичен и виден в поиске. */
    val allowJoinRequests = bool("allow_join_requests").default(true)
    /** Заполняется автоматически датой создания клуба, руками не задаётся. */
    val foundedAt = long("founded_at")
    val updatedAt = long("updated_at").default(0L)

    override val primaryKey = PrimaryKey(id)
}
