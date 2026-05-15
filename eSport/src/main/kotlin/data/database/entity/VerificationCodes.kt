package com.competra.data.database.entity

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object VerificationCodes : Table("verification_codes") {
    val id = integer("id").autoIncrement()
    val email = varchar("email", 255).uniqueIndex()
    val code = varchar("code", 6)
    val createdAt = datetime("created_at")

}
