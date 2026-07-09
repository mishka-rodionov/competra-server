package com.competra.data.database.entity.rating

import com.competra.data.database.entity.Competitions
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/** Соревнования, включённые в состав рейтинга. */
object RatingCompetitions : Table("rating_competitions") {
    val id = varchar("id", 36)
    val ratingId = varchar("rating_id", 36).references(RatingSeries.id, onDelete = ReferenceOption.CASCADE)
    val competitionId = varchar("competition_id", 36).references(Competitions.id, onDelete = ReferenceOption.CASCADE)
    val addedAt = long("added_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("rating_competitions_rating_competition_idx", ratingId, competitionId)
    }
}
