package com.competra.data.database.entity.rating

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/** Канонический список групп рейтинга, на которые мапятся группы конкретных соревнований. */
object RatingGroups : Table("rating_groups") {
    val id = long("id").autoIncrement()
    val ratingId = varchar("rating_id", 36).references(RatingSeries.id, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 200)
    val gender = varchar("gender", 20).nullable()
    val minAge = integer("min_age").nullable()
    val maxAge = integer("max_age").nullable()
    val orderIndex = integer("order_index").default(0)

    override val primaryKey = PrimaryKey(id)
}
