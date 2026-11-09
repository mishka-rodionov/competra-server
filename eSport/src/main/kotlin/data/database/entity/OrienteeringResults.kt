package com.competra.data.database.entity

import org.jetbrains.exposed.v1.core.Table

object OrienteeringResults : Table("orienteering_results") {
    val id = varchar("id", 200)
    val competitionId = varchar("competition_id", 36)
        .references(Competitions.id, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE)
    val groupId = long("group_id")
    val participantId = varchar("participant_id", 200)
    val startTime = long("start_time").nullable()
    val finishTime = long("finish_time").nullable()
    val totalTime = long("total_time").nullable()
    val rank = integer("rank").nullable()
    val status = varchar("status", 50)
    val penaltyTime = long("penalty_time")
    val totalScore = integer("total_score").nullable()
    val scorePenalty = integer("score_penalty").default(0)
    val isEditable = bool("is_editable")
    val isEdited = bool("is_edited")

    /** Статус выставлен организатором вручную («Снят» / «Засчитать») — пересчёт его не перетирает. */
    val isStatusManual = bool("is_status_manual").default(false)

    /** Время считывания чипа на финише (Unix ms); null — неизвестно. */
    val readAt = long("read_at").nullable()
    val updatedAt = long("updated_at").default(0L)

    override val primaryKey = PrimaryKey(id)
}
