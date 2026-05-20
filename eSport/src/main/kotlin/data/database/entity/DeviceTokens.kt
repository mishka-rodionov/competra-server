package com.competra.data.database.entity

import org.jetbrains.exposed.sql.Table

object DeviceTokens : Table("device_tokens") {
    val token = varchar("token", 512)
    val userId = varchar("user_id", 200)
    val platform = varchar("platform", 16)
    val appVersion = varchar("app_version", 32).nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(token)
}
