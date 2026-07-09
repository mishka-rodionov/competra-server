package com.competra.data.database.entity.rating

import com.competra.data.database.entity.ParticipantGroups
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/** Соответствие группы конкретного соревнования (в составе рейтинга) канонической группе рейтинга. */
object RatingGroupMappings : Table("rating_group_mappings") {
    val id = varchar("id", 36)
    val ratingCompetitionId = varchar("rating_competition_id", 36)
        .references(RatingCompetitions.id, onDelete = ReferenceOption.CASCADE)
    val participantGroupId = long("participant_group_id").references(ParticipantGroups.id, onDelete = ReferenceOption.CASCADE)
    val ratingGroupId = long("rating_group_id").references(RatingGroups.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("rating_group_mappings_competition_group_idx", ratingCompetitionId, participantGroupId)
    }
}
