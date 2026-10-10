package com.competra.data.database.entity

import org.jetbrains.exposed.v1.core.Table

object RefreshTokens : Table("refresh_tokens") {
    val token = varchar("token", 100)
    val userId = varchar("user_id", 200)
    val expiresAt = long("expires_at")

    override val primaryKey = PrimaryKey(token)
}
