package com.competra.tracking

import com.competra.data.events.ResultSavedEvent
import com.competra.data.exception.ForbiddenException
import com.competra.data.exception.ServiceUnavailableException
import com.competra.data.exception.UnprocessableEntityException
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Как часто клиент бегуна отправляет батчи. */
const val UPLOAD_INTERVAL_SEC = 10

/** Максимум точек в одном батче. */
const val MAX_POINTS_PER_BATCH = 300

/** Точки не старше этого до старта сессии принимаются (GPS мог поймать фикс до нажатия «старт»). */
private val POINT_PAST_TOLERANCE_MS = TimeUnit.MINUTES.toMillis(5)

/** Допуск на расхождение часов телефона и сервера «в будущее». */
private val POINT_FUTURE_TOLERANCE_MS = TimeUnit.MINUTES.toMillis(1)

/** Запас к контрольному времени, после которого сессия закрывается сторожем. */
private val CONTROL_TIME_GRACE_MS = TimeUnit.MINUTES.toMillis(60)

/** Максимальная длительность сессии, если контрольное время не задано. */
private val DEFAULT_MAX_DURATION_MS = TimeUnit.HOURS.toMillis(6)

/** Сессия без единой точки дольше этого закрывается сторожем. */
private val INACTIVITY_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(45)

/** Окно, в которое можно включить трекинг: соревнование ± сутки (часовые пояса, многодневки). */
private val COMPETITION_WINDOW_MS = TimeUnit.DAYS.toMillis(1)

/** Закрытые сессии держатся в памяти для зрителей `live` столько, потом — только архив `tracks`. */
val RECENT_CLOSED_WINDOW_MS: Long = TimeUnit.HOURS.toMillis(1)

/**
 * Сколько после закрытия сессии принимаются досланные точки (бегун финишировал без связи, а
 * сессию уже закрыл результат). Точки дописываются в сохранённый трек.
 */
private val LATE_POINTS_WINDOW_MS = TimeUnit.HOURS.toMillis(2)

/** Статусы соревнования, при которых трекинг уже не включить. */
private val CLOSED_COMPETITION_STATUSES = setOf("FINISHED", "ARCHIVED")

/**
 * Логика онлайн-трекинга: старт/возобновление сессии, приём батчей точек, закрытие (вручную,
 * по результату, сторожем) и выдача треков зрителям.
 *
 * Источник истины — БД трекинга ([repository]); [store] — копия для быстрых ответов зрителям.
 * Основная БД ([participants]) читается только при старте сессии.
 */
class LiveTrackService(
    private val repository: LiveTrackRepository,
    private val participants: ParticipantDirectory,
    private val store: LiveTrackStore,
    private val clock: () -> Long = System::currentTimeMillis
) {

    @Volatile
    private var ready = false

    /**
     * Создаёт схему и поднимает в память активные и недавно закрытые сессии. До успешного вызова
     * все операции отвечают 503.
     */
    suspend fun initialize() {
        repository.createSchema()
        repository.loadRecent(clock() - RECENT_CLOSED_WINDOW_MS).forEach { (session, points) -> store.load(session, points) }
        ready = true
    }

    /** Готов ли сервис (схема создана, память восстановлена). */
    fun isReady(): Boolean = ready

    /**
     * Старт трекинга участником. Если у участника уже есть активная сессия (перезапуск приложения),
     * возвращается она.
     */
    suspend fun start(userId: String, request: StartSessionRequest): SessionResponse {
        requireReady()
        val competitionId = request.competitionId?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("competitionId is required")
        if (request.consent != true) throw UnprocessableEntityException("Нужно согласие на публикацию трека")
        val participantId = request.participantId?.takeIf { it.isNotBlank() }
            ?: resolveParticipantId(competitionId, userId)

        val participant = participants.find(participantId) ?: throw NotFoundException("Участник не найден")
        if (participant.competitionId != competitionId) throw BadRequestException("Участник из другого соревнования")
        if (participant.userId != userId) throw ForbiddenException("Можно включить трекинг только для своего участия")

        repository.findActiveByParticipant(participantId)?.let { active ->
            store.upsertSession(active)
            return active.toSessionResponse()
        }

        val now = clock()
        val windowStart = participant.competitionStartDate - COMPETITION_WINDOW_MS
        val windowEnd = (participant.competitionEndDate ?: participant.competitionStartDate) + COMPETITION_WINDOW_MS
        if (now !in windowStart..windowEnd || participant.competitionStatus in CLOSED_COMPETITION_STATUSES) {
            throw UnprocessableEntityException("Трекинг доступен только в день соревнования")
        }
        if (participant.hasFinalResult) throw UnprocessableEntityException("У участника уже есть результат")
        val distanceId = participant.distanceId
            ?: throw UnprocessableEntityException("Группе участника не назначена дистанция")

        val session = LiveTrackSession(
            id = UUID.randomUUID().toString(),
            competitionId = competitionId,
            distanceId = distanceId,
            distanceName = participant.distanceName,
            participantId = participantId,
            userId = userId,
            displayName = participant.displayName,
            groupName = participant.groupName,
            startNumber = participant.startNumber,
            status = LiveTrackStatus.ACTIVE,
            closeReason = null,
            startedAt = now,
            closedAt = null,
            lastPointAt = null,
            deadlineAt = deadlineFor(participant, now),
            lastBatchSeq = 0,
            consentAt = now
        )
        try {
            repository.insert(session)
        } catch (e: Exception) {
            // Гонка двух параллельных стартов: уникальный индекс активной сессии участника.
            val active = repository.findActiveByParticipant(participantId) ?: throw e
            store.upsertSession(active)
            return active.toSessionResponse()
        }
        store.upsertSession(session)
        return session.toSessionResponse()
    }

    /** Принимает батч точек от владельца сессии. Повторный батч подтверждается без записи. */
    suspend fun appendPoints(userId: String, sessionId: String, request: PointsBatchRequest): PointsAckResponse {
        requireReady()
        val batchSeq = request.batchSeq?.takeIf { it > 0 } ?: throw BadRequestException("batchSeq must be > 0")
        val rawPoints = request.points ?: throw BadRequestException("points are required")
        if (rawPoints.size > MAX_POINTS_PER_BATCH) {
            throw BadRequestException("Не больше $MAX_POINTS_PER_BATCH точек в батче")
        }
        val session = findSession(sessionId)
        if (session.userId != userId) throw ForbiddenException("Чужая сессия трекинга")
        if (batchSeq <= session.lastBatchSeq) return PointsAckResponse(session.status, session.closeReason, batchSeq)
        if (session.status != LiveTrackStatus.ACTIVE) return appendLatePoints(session, batchSeq, rawPoints)

        val now = clock()
        val points = rawPoints.mapNotNull { it.toValidPoint(session.startedAt - POINT_PAST_TOLERANCE_MS, now + POINT_FUTURE_TOLERANCE_MS) }
        val lastPointAt = (points.maxOfOrNull { it.t } ?: session.lastPointAt)
            ?.let { maxOf(it, session.lastPointAt ?: it) }
        val accepted = repository.appendPoints(sessionId, batchSeq, points, lastPointAt)
        if (!accepted) {
            // Повтор или сессию закрыли между чтением и записью — отвечаем актуальным состоянием;
            // во втором случае точки ещё можно дописать в закрытый трек.
            val current = repository.findById(sessionId) ?: session
            store.upsertSession(current)
            if (current.status != LiveTrackStatus.ACTIVE && batchSeq > current.lastBatchSeq) {
                return appendLatePoints(current, batchSeq, rawPoints)
            }
            return PointsAckResponse(current.status, current.closeReason, batchSeq)
        }
        store.addPoints(session.copy(lastBatchSeq = batchSeq, lastPointAt = lastPointAt), points)
        return PointsAckResponse(LiveTrackStatus.ACTIVE, null, batchSeq)
    }

    /**
     * Батч для уже закрытой сессии: в течение [LATE_POINTS_WINDOW_MS] после закрытия точки,
     * снятые до момента закрытия, дописываются в трек; иначе батч подтверждается без записи.
     * Клиент по статусу в ответе всё равно прекращает запись GPS и только досылает буфер.
     */
    private suspend fun appendLatePoints(session: LiveTrackSession, batchSeq: Int, rawPoints: List<PointRequest>): PointsAckResponse {
        val closedAt = session.closedAt
        val ack = PointsAckResponse(session.status, session.closeReason, batchSeq)
        if (closedAt == null || clock() - closedAt > LATE_POINTS_WINDOW_MS) return ack
        val points = rawPoints.mapNotNull { it.toValidPoint(session.startedAt - POINT_PAST_TOLERANCE_MS, closedAt + POINT_FUTURE_TOLERANCE_MS) }
        val updated = repository.appendLatePoints(session.id, batchSeq, points) ?: return ack
        store.addPoints(updated, points)
        return ack
    }

    /** Ручная остановка трекинга владельцем. */
    suspend fun stop(userId: String, sessionId: String): PointsAckResponse {
        requireReady()
        val session = findSession(sessionId)
        if (session.userId != userId) throw ForbiddenException("Чужая сессия трекинга")
        val closed = close(session.id, LiveTrackStatus.STOPPED, CloseReason.MANUAL) ?: repository.findById(sessionId) ?: session
        return PointsAckResponse(closed.status, closed.closeReason, closed.lastBatchSeq)
    }

    /** Событие из основного приложения: финальный результат → закрыть активную сессию участника. */
    suspend fun onResultSaved(event: ResultSavedEvent) {
        if (!ready || event.resultStatus !in FINAL_RESULT_STATUSES) return
        val active = repository.findActiveByParticipant(event.participantId) ?: return
        if (active.competitionId != event.competitionId) return
        close(active.id, LiveTrackStatus.FINISHED, CloseReason.RESULT_SAVED)
    }

    /**
     * Сторож: закрывает сессии с истёкшим сроком или без точек дольше [INACTIVITY_TIMEOUT_MS]
     * и вытесняет из памяти давно закрытые.
     *
     * @return число закрытых сессий.
     */
    suspend fun sweep(): Int {
        if (!ready) return 0
        val now = clock()
        var closedCount = 0
        repository.findExpired(now, now - INACTIVITY_TIMEOUT_MS).forEach { session ->
            val reason = if (session.deadlineAt < now) CloseReason.CONTROL_TIME else CloseReason.INACTIVITY
            if (close(session.id, LiveTrackStatus.TIMED_OUT, reason) != null) closedCount++
        }
        store.evictClosedBefore(now - RECENT_CLOSED_WINDOW_MS)
        return closedCount
    }

    /** Дистанции соревнования, по которым есть треки. */
    suspend fun distances(competitionId: String): List<TrackedDistanceResponse> {
        requireReady()
        return repository.distanceSummary(competitionId)
    }

    /** Живой снимок дистанции из памяти. */
    fun live(distanceId: Long, cursor: String?): LiveSnapshotResponse {
        requireReady()
        return store.snapshot(distanceId, cursor, clock())
    }

    /** Архив треков дистанции. Треки активных сессий берутся из памяти. */
    suspend fun tracks(distanceId: Long): List<ArchivedTrackResponse> {
        requireReady()
        return repository.listByDistance(distanceId).map { (session, encoded) ->
            val track = encoded ?: TrackCodec.encode(session.startedAt, store.pointsOf(session.id))
            ArchivedTrackResponse(
                sessionId = session.id,
                participantId = session.participantId,
                displayName = session.displayName,
                groupName = session.groupName,
                startNumber = session.startNumber,
                status = session.status,
                closeReason = session.closeReason,
                startedAt = session.startedAt,
                closedAt = session.closedAt,
                trackEncoded = track
            )
        }
    }

    private suspend fun close(sessionId: String, status: LiveTrackStatus, reason: CloseReason): LiveTrackSession? {
        val closed = repository.close(sessionId, status, reason, clock()) ?: return null
        store.upsertSession(closed)
        return closed
    }

    /** Участник пользователя в соревновании, если трекинг включают без явного `participantId`. */
    private suspend fun resolveParticipantId(competitionId: String, userId: String): String {
        val ids = participants.findIdsByUser(competitionId, userId)
        return when (ids.size) {
            0 -> throw UnprocessableEntityException("Вы не зарегистрированы на это соревнование")
            1 -> ids.single()
            else -> throw UnprocessableEntityException("Несколько участий в соревновании — укажите participantId")
        }
    }

    private suspend fun findSession(sessionId: String): LiveTrackSession =
        store.get(sessionId)
            ?: repository.findById(sessionId)?.also { store.upsertSession(it) }
            ?: throw NotFoundException("Сессия трекинга не найдена")

    private fun requireReady() {
        if (!ready) throw ServiceUnavailableException("Трекинг запускается, повторите позже")
    }

    private fun deadlineFor(participant: ParticipantContext, now: Long): Long {
        val start = maxOf(now, participant.plannedStartTime ?: now)
        val controlTime = participant.controlTimeMinutes?.takeIf { it > 0 }
        return if (controlTime != null) {
            start + TimeUnit.MINUTES.toMillis(controlTime.toLong()) + CONTROL_TIME_GRACE_MS
        } else {
            start + DEFAULT_MAX_DURATION_MS
        }
    }
}

private fun LiveTrackSession.toSessionResponse() = SessionResponse(
    sessionId = id,
    status = status,
    closeReason = closeReason,
    lastBatchSeq = lastBatchSeq,
    uploadIntervalSec = UPLOAD_INTERVAL_SEC,
    deadlineAt = deadlineAt
)

/** Точка с координатами в допустимых пределах и временем в окне [minT, maxT], иначе `null`. */
private fun PointRequest.toValidPoint(minT: Long, maxT: Long): TrackPoint? {
    val time = t ?: return null
    val latitude = lat ?: return null
    val longitude = lon ?: return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    if (latitude.isNaN() || longitude.isNaN()) return null
    if (time !in minT..maxT) return null
    return TrackPoint(time, latitude, longitude, acc?.takeIf { it >= 0f && !it.isNaN() })
}
