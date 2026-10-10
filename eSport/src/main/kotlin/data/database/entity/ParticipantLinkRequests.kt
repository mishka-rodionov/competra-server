package com.competra.data.database.entity

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Заявка пользователя «этот участник протокола — я». После одобрения организатором
 * в [OrienteeringParticipants.userId] записывается [userId] заявителя.
 */
object ParticipantLinkRequests : Table("participant_link_requests") {
    val id = varchar("id", 36)
    val participantId = varchar("participant_id", 200)
        .references(OrienteeringParticipants.id, onDelete = ReferenceOption.CASCADE)
    /** Денормализовано из участника — список заявок и счётчики по соревнованию без join. */
    val competitionId = varchar("competition_id", 36)
        .references(Competitions.id, onDelete = ReferenceOption.CASCADE)
    val userId = varchar("user_id", 200)
    /** PENDING | APPROVED | REJECTED | CANCELLED | UNLINKED */
    val status = varchar("status", 20).default("PENDING")
    /** SUGGESTION — из подсказок по имени, MANUAL — пользователь сам выбрал участника в протоколе. */
    val requestSource = varchar("source", 20)
    /** Причина отклонения — видна заявителю. */
    val comment = varchar("comment", 500).nullable()
    val reviewedBy = varchar("reviewed_by", 200).nullable()
    val reviewedAt = long("reviewed_at").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at").default(0L)

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, participantId, status)
        index(false, userId, status)
        index(false, competitionId, status)
    }
}
