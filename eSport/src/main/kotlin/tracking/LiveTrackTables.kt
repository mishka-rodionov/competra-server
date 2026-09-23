package com.competra.tracking

import org.jetbrains.exposed.sql.Table

/** Сессии онлайн-трекинга (БД `competra_tracking`). См. [LiveTrackSession]. */
object LiveTrackSessions : Table("live_track_sessions") {
    val id = varchar("id", 36)
    val competitionId = varchar("competition_id", 36)
    val distanceId = long("distance_id")
    val distanceName = varchar("distance_name", 200).nullable()
    val participantId = varchar("participant_id", 200)
    val userId = varchar("user_id", 200)
    val displayName = varchar("display_name", 400)
    val groupName = varchar("group_name", 200).nullable()
    val startNumber = integer("start_number").nullable()
    val status = varchar("status", 20)
    val closeReason = varchar("close_reason", 30).nullable()
    val startedAt = long("started_at")
    val closedAt = long("closed_at").nullable()
    val lastPointAt = long("last_point_at").nullable()
    val deadlineAt = long("deadline_at")
    val lastBatchSeq = integer("last_batch_seq").default(0)
    val consentAt = long("consent_at")

    /** Трек в формате [TrackCodec] от [startedAt]; заполняется при закрытии сессии. */
    val trackEncoded = text("track_encoded").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, distanceId, status)
        index(false, competitionId)
        index(false, participantId, status)
    }
}

/**
 * Точки активных сессий. При закрытии сессии сворачиваются в [LiveTrackSessions.trackEncoded]
 * и удаляются — таблица не растёт от соревнования к соревнованию.
 */
object LiveTrackPoints : Table("live_track_points") {
    val id = long("id").autoIncrement()
    val sessionId = varchar("session_id", 36).index()
    val t = long("t")
    val lat = double("lat")
    val lon = double("lon")
    val accuracy = float("accuracy_m").nullable()

    override val primaryKey = PrimaryKey(id)
}
