package com.sportenth.data.database.entity

import org.jetbrains.exposed.sql.Table

object SplitTimes : Table("split_times") {
    val id = integer("id").autoIncrement()
    val resultId = varchar("result_id", 200)
    val controlPoint = integer("control_point")
    val timestamp = long("timestamp")

    override val primaryKey = PrimaryKey(id)
}
