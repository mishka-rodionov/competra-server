package com.competra.tracking

import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Треки в памяти процесса трекинга: зрители (`live`) обслуживаются отсюда, без БД.
 *
 * Курсор — `epoch:seq`, где `seq` — номер точки **в порядке добавления в память**, а не id строки
 * в БД: при параллельных батчах точка с меньшим id может закоммититься позже и проскочить мимо
 * курсора, выданного по id. `epoch` меняется при каждом старте процесса — старый курсор после
 * перезапуска распознаётся, и клиент получает `reset` с полными треками.
 *
 * Хранит активные сессии и недавно закрытые (их вытесняет [evictClosedBefore]); архив старше —
 * в БД через `tracks`.
 */
class LiveTrackStore(private val epoch: String = UUID.randomUUID().toString().take(8)) {

    private class SeqPoint(val seq: Long, val point: TrackPoint)

    private class Entry(var session: LiveTrackSession) {
        val points = ArrayList<SeqPoint>()
    }

    private val lock = ReentrantReadWriteLock()
    private val entries = HashMap<String, Entry>()
    private var lastSeq = 0L

    /** Добавляет или обновляет метаданные сессии (точки не трогает). */
    fun upsertSession(session: LiveTrackSession) = lock.write {
        entries.getOrPut(session.id) { Entry(session) }.session = session
    }

    /** Кладёт в память сессию вместе с её треком (восстановление после старта процесса). */
    fun load(session: LiveTrackSession, points: List<TrackPoint>) = lock.write {
        val entry = Entry(session)
        points.sortedBy { it.t }.forEach { entry.points.add(SeqPoint(++lastSeq, it)) }
        entries[session.id] = entry
    }

    /** Добавляет принятые точки и обновляет метаданные сессии (создаёт запись, если её нет). */
    fun addPoints(session: LiveTrackSession, points: List<TrackPoint>) = lock.write {
        val entry = entries.getOrPut(session.id) { Entry(session) }
        entry.session = session
        points.forEach { entry.points.add(SeqPoint(++lastSeq, it)) }
    }

    /** Сессия из памяти или `null`. */
    fun get(sessionId: String): LiveTrackSession? = lock.read { entries[sessionId]?.session }

    /** Точки сессии, отсортированные по времени фикса. */
    fun pointsOf(sessionId: String): List<TrackPoint> = lock.read {
        entries[sessionId]?.points?.map { it.point }?.sortedBy { it.t }.orEmpty()
    }

    /**
     * Снимок дистанции для зрителя: метаданные всех её сессий и точки после [cursor].
     * Непонятный или чужой (от прошлого запуска) курсор → `reset = true` и полные треки.
     */
    fun snapshot(distanceId: Long, cursor: String?, serverTime: Long): LiveSnapshotResponse = lock.read {
        val since = parseCursor(cursor)
        val reset = cursor != null && since == null
        val fromSeq = since ?: 0L
        val sessions = entries.values
            .filter { it.session.distanceId == distanceId }
            .sortedBy { it.session.startedAt }
            .map { entry ->
                val newPoints = entry.points.filter { it.seq > fromSeq }.map { it.point }
                val ordered = if (fromSeq == 0L) newPoints.sortedBy { it.t } else newPoints
                entry.session.toLiveResponse(ordered)
            }
        LiveSnapshotResponse(cursor = "$epoch:$lastSeq", reset = reset, serverTime = serverTime, sessions = sessions)
    }

    /** Убирает из памяти сессии, закрытые раньше [cutoff]. */
    fun evictClosedBefore(cutoff: Long) = lock.write {
        entries.values.removeAll { entry -> entry.session.closedAt?.let { it < cutoff } ?: false }
    }

    private fun parseCursor(cursor: String?): Long? {
        if (cursor.isNullOrBlank()) return 0L
        val parts = cursor.split(':')
        if (parts.size != 2 || parts[0] != epoch) return null
        return parts[1].toLongOrNull()?.takeIf { it in 0..lastSeq }
    }
}

private fun LiveTrackSession.toLiveResponse(points: List<TrackPoint>) = LiveSessionResponse(
    sessionId = id,
    participantId = participantId,
    displayName = displayName,
    groupName = groupName,
    startNumber = startNumber,
    status = status,
    closeReason = closeReason,
    startedAt = startedAt,
    lastPointAt = lastPointAt,
    points = points.map { listOf(it.t, it.lat, it.lon) }
)
