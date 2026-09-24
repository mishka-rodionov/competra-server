package com.competra.tracking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Хранилище сессий и точек в БД трекинга. Интерфейс — ради тестов [LiveTrackService] без БД. */
interface LiveTrackRepository {

    /** Создаёт таблицы и индексы, если их нет. */
    suspend fun createSchema()

    /** Активная сессия участника или `null`. */
    suspend fun findActiveByParticipant(participantId: String): LiveTrackSession?

    /** Сессия по id или `null`. */
    suspend fun findById(sessionId: String): LiveTrackSession?

    /** Сохраняет новую сессию. */
    suspend fun insert(session: LiveTrackSession)

    /**
     * Атомарно принимает батч: сдвигает `last_batch_seq` только если [batchSeq] больше уже
     * принятого и сессия активна, и в той же транзакции пишет точки.
     *
     * @return `false`, если батч — повтор (или сессия уже не активна); точки тогда не пишутся.
     */
    suspend fun appendPoints(sessionId: String, batchSeq: Int, points: List<TrackPoint>, lastPointAt: Long?): Boolean

    /**
     * Дописывает досланные точки в трек **закрытой** сессии (`track_encoded`): бегун финишировал
     * без связи, а сессию уже закрыл результат. Идемпотентно по [batchSeq], как [appendPoints].
     *
     * @return обновлённая сессия или `null`, если батч — повтор (или сессия активна).
     */
    suspend fun appendLatePoints(sessionId: String, batchSeq: Int, points: List<TrackPoint>): LiveTrackSession?

    /**
     * Закрывает **активную** сессию: сворачивает её точки в `track_encoded` и удаляет их.
     *
     * @return закрытая сессия или `null`, если она уже была закрыта (гонка стоп/результат/сторож).
     */
    suspend fun close(sessionId: String, status: LiveTrackStatus, reason: CloseReason, closedAt: Long): LiveTrackSession?

    /** Активные сессии, у которых вышел срок или нет точек с момента [inactiveSince]. */
    suspend fun findExpired(now: Long, inactiveSince: Long): List<LiveTrackSession>

    /** Активные сессии и закрытые после [closedAfter] — с треками, для восстановления памяти. */
    suspend fun loadRecent(closedAfter: Long): List<Pair<LiveTrackSession, List<TrackPoint>>>

    /** Все сессии дистанции с треками (архив). У активных `trackEncoded` = `null`. */
    suspend fun listByDistance(distanceId: Long): List<ArchivedSession>

    /** Дистанции соревнования, по которым есть сессии. */
    suspend fun distanceSummary(competitionId: String): List<TrackedDistanceResponse>
}

/** Реализация [LiveTrackRepository] на Exposed поверх БД `competra_tracking`. */
class ExposedLiveTrackRepository(private val db: Database) : LiveTrackRepository {

    override suspend fun createSchema() = tx {
        SchemaUtils.create(LiveTrackSessions, LiveTrackPoints)
        // Не больше одной активной сессии на участника — защита от гонки двух параллельных стартов.
        exec(
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_live_track_sessions_active_participant " +
                "ON live_track_sessions (participant_id) WHERE status = 'ACTIVE'"
        )
        Unit
    }

    override suspend fun findActiveByParticipant(participantId: String): LiveTrackSession? = tx {
        LiveTrackSessions.selectAll()
            .where { (LiveTrackSessions.participantId eq participantId) and (LiveTrackSessions.status eq LiveTrackStatus.ACTIVE.name) }
            .singleOrNull()?.toSession()
    }

    override suspend fun findById(sessionId: String): LiveTrackSession? = tx {
        LiveTrackSessions.selectAll().where { LiveTrackSessions.id eq sessionId }.singleOrNull()?.toSession()
    }

    override suspend fun insert(session: LiveTrackSession) = tx {
        LiveTrackSessions.insert {
            it[id] = session.id
            it[competitionId] = session.competitionId
            it[distanceId] = session.distanceId
            it[distanceName] = session.distanceName
            it[participantId] = session.participantId
            it[userId] = session.userId
            it[displayName] = session.displayName
            it[groupName] = session.groupName
            it[startNumber] = session.startNumber
            it[status] = session.status.name
            it[closeReason] = session.closeReason?.name
            it[startedAt] = session.startedAt
            it[closedAt] = session.closedAt
            it[lastPointAt] = session.lastPointAt
            it[deadlineAt] = session.deadlineAt
            it[lastBatchSeq] = session.lastBatchSeq
            it[consentAt] = session.consentAt
        }
        Unit
    }

    override suspend fun appendPoints(
        sessionId: String,
        batchSeq: Int,
        points: List<TrackPoint>,
        lastPointAt: Long?
    ): Boolean = tx {
        val updated = LiveTrackSessions.update({
            (LiveTrackSessions.id eq sessionId) and
                (LiveTrackSessions.status eq LiveTrackStatus.ACTIVE.name) and
                (LiveTrackSessions.lastBatchSeq less batchSeq)
        }) {
            it[LiveTrackSessions.lastBatchSeq] = batchSeq
            if (lastPointAt != null) it[LiveTrackSessions.lastPointAt] = lastPointAt
        }
        if (updated == 0) return@tx false
        LiveTrackPoints.batchInsert(points, shouldReturnGeneratedValues = false) { point ->
            this[LiveTrackPoints.sessionId] = sessionId
            this[LiveTrackPoints.t] = point.t
            this[LiveTrackPoints.lat] = point.lat
            this[LiveTrackPoints.lon] = point.lon
            this[LiveTrackPoints.accuracy] = point.accuracy
        }
        true
    }

    override suspend fun appendLatePoints(
        sessionId: String,
        batchSeq: Int,
        points: List<TrackPoint>
    ): LiveTrackSession? = tx {
        val row = LiveTrackSessions.selectAll()
            .where {
                (LiveTrackSessions.id eq sessionId) and
                    (LiveTrackSessions.status neq LiveTrackStatus.ACTIVE.name) and
                    (LiveTrackSessions.lastBatchSeq less batchSeq)
            }
            .forUpdate()
            .singleOrNull() ?: return@tx null
        val session = row.toSession()
        val merged = (TrackCodec.decode(session.startedAt, row[LiveTrackSessions.trackEncoded]) + points).sortedBy { it.t }
        val lastPointAt = merged.maxOfOrNull { it.t } ?: session.lastPointAt
        LiveTrackSessions.update({ LiveTrackSessions.id eq sessionId }) {
            it[LiveTrackSessions.lastBatchSeq] = batchSeq
            it[LiveTrackSessions.lastPointAt] = lastPointAt
            it[LiveTrackSessions.trackEncoded] = TrackCodec.encode(session.startedAt, merged)
        }
        session.copy(lastBatchSeq = batchSeq, lastPointAt = lastPointAt)
    }

    override suspend fun close(
        sessionId: String,
        status: LiveTrackStatus,
        reason: CloseReason,
        closedAt: Long
    ): LiveTrackSession? = tx {
        val session = LiveTrackSessions.selectAll()
            .where { (LiveTrackSessions.id eq sessionId) and (LiveTrackSessions.status eq LiveTrackStatus.ACTIVE.name) }
            .forUpdate()
            .singleOrNull()?.toSession()
            ?: return@tx null
        val encoded = TrackCodec.encode(session.startedAt, loadPointsOf(sessionId))
        LiveTrackSessions.update({ LiveTrackSessions.id eq sessionId }) {
            it[LiveTrackSessions.status] = status.name
            it[LiveTrackSessions.closeReason] = reason.name
            it[LiveTrackSessions.closedAt] = closedAt
            it[LiveTrackSessions.trackEncoded] = encoded
        }
        LiveTrackPoints.deleteWhere { LiveTrackPoints.sessionId eq sessionId }
        session.copy(status = status, closeReason = reason, closedAt = closedAt)
    }

    override suspend fun findExpired(now: Long, inactiveSince: Long): List<LiveTrackSession> = tx {
        LiveTrackSessions.selectAll()
            .where { LiveTrackSessions.status eq LiveTrackStatus.ACTIVE.name }
            .map { it.toSession() }
            .filter { it.deadlineAt < now || (it.lastPointAt ?: it.startedAt) < inactiveSince }
    }

    override suspend fun loadRecent(closedAfter: Long): List<Pair<LiveTrackSession, List<TrackPoint>>> = tx {
        LiveTrackSessions.selectAll()
            .where {
                (LiveTrackSessions.status eq LiveTrackStatus.ACTIVE.name) or (LiveTrackSessions.closedAt greater closedAfter)
            }
            .map { row ->
                val session = row.toSession()
                val points = if (session.status == LiveTrackStatus.ACTIVE) {
                    loadPointsOf(session.id)
                } else {
                    TrackCodec.decode(session.startedAt, row[LiveTrackSessions.trackEncoded])
                }
                session to points
            }
    }

    override suspend fun listByDistance(distanceId: Long): List<ArchivedSession> = tx {
        LiveTrackSessions.selectAll()
            .where { LiveTrackSessions.distanceId eq distanceId }
            .orderBy(LiveTrackSessions.startedAt)
            .map { ArchivedSession(it.toSession(), it[LiveTrackSessions.trackEncoded]) }
    }

    override suspend fun distanceSummary(competitionId: String): List<TrackedDistanceResponse> = tx {
        LiveTrackSessions.selectAll()
            .where { LiveTrackSessions.competitionId eq competitionId }
            .map { it.toSession() }
            .groupBy { it.distanceId }
            .map { (distanceId, sessions) ->
                TrackedDistanceResponse(
                    distanceId = distanceId,
                    name = sessions.last().distanceName,
                    activeCount = sessions.count { it.status == LiveTrackStatus.ACTIVE },
                    totalCount = sessions.size
                )
            }
            .sortedBy { it.name.orEmpty() }
    }

    private fun Transaction.loadPointsOf(sessionId: String): List<TrackPoint> =
        LiveTrackPoints.selectAll()
            .where { LiveTrackPoints.sessionId eq sessionId }
            .orderBy(LiveTrackPoints.t)
            .map { TrackPoint(it[LiveTrackPoints.t], it[LiveTrackPoints.lat], it[LiveTrackPoints.lon], it[LiveTrackPoints.accuracy]) }

    /**
     * Блокирующая транзакция на IO-диспетчере. Не `newSuspendedTransaction`: при недоступном пуле
     * Hikari тот падает фатальной ошибкой корутин вместо обычного исключения.
     */
    private suspend fun <T> tx(block: Transaction.() -> T): T = withContext(Dispatchers.IO) { transaction(db) { block() } }
}

private fun ResultRow.toSession() = LiveTrackSession(
    id = this[LiveTrackSessions.id],
    competitionId = this[LiveTrackSessions.competitionId],
    distanceId = this[LiveTrackSessions.distanceId],
    distanceName = this[LiveTrackSessions.distanceName],
    participantId = this[LiveTrackSessions.participantId],
    userId = this[LiveTrackSessions.userId],
    displayName = this[LiveTrackSessions.displayName],
    groupName = this[LiveTrackSessions.groupName],
    startNumber = this[LiveTrackSessions.startNumber],
    status = LiveTrackStatus.valueOf(this[LiveTrackSessions.status]),
    closeReason = this[LiveTrackSessions.closeReason]?.let { CloseReason.valueOf(it) },
    startedAt = this[LiveTrackSessions.startedAt],
    closedAt = this[LiveTrackSessions.closedAt],
    lastPointAt = this[LiveTrackSessions.lastPointAt],
    deadlineAt = this[LiveTrackSessions.deadlineAt],
    lastBatchSeq = this[LiveTrackSessions.lastBatchSeq],
    consentAt = this[LiveTrackSessions.consentAt]
)
