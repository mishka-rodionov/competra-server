package com.competra.tracking

import com.competra.data.events.ResultSavedEvent
import com.competra.data.exception.ForbiddenException
import com.competra.data.exception.ServiceUnavailableException
import com.competra.data.exception.UnprocessableEntityException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val NOW = 1_790_000_000_000L

class LiveTrackServiceTest {

    private class FakeRepository : LiveTrackRepository {
        val sessions = LinkedHashMap<String, LiveTrackSession>()
        val points = HashMap<String, MutableList<TrackPoint>>()
        val encoded = HashMap<String, String>()

        override suspend fun createSchema() = Unit
        override suspend fun findActiveByParticipant(participantId: String) =
            sessions.values.firstOrNull { it.participantId == participantId && it.status == LiveTrackStatus.ACTIVE }
        override suspend fun findById(sessionId: String) = sessions[sessionId]
        override suspend fun insert(session: LiveTrackSession) { sessions[session.id] = session }
        override suspend fun appendPoints(sessionId: String, batchSeq: Int, points: List<TrackPoint>, lastPointAt: Long?): Boolean {
            val s = sessions.getValue(sessionId)
            if (s.status != LiveTrackStatus.ACTIVE || s.lastBatchSeq >= batchSeq) return false
            sessions[sessionId] = s.copy(lastBatchSeq = batchSeq, lastPointAt = lastPointAt)
            this.points.getOrPut(sessionId) { mutableListOf() } += points
            return true
        }
        override suspend fun appendLatePoints(sessionId: String, batchSeq: Int, points: List<TrackPoint>): LiveTrackSession? {
            val s = sessions.getValue(sessionId)
            if (s.status == LiveTrackStatus.ACTIVE || s.lastBatchSeq >= batchSeq) return null
            val merged = (TrackCodec.decode(s.startedAt, encoded[sessionId]) + points).sortedBy { it.t }
            encoded[sessionId] = TrackCodec.encode(s.startedAt, merged)
            return s.copy(lastBatchSeq = batchSeq).also { sessions[sessionId] = it }
        }
        override suspend fun close(sessionId: String, status: LiveTrackStatus, reason: CloseReason, closedAt: Long): LiveTrackSession? {
            val s = sessions[sessionId]?.takeIf { it.status == LiveTrackStatus.ACTIVE } ?: return null
            val closed = s.copy(status = status, closeReason = reason, closedAt = closedAt)
            sessions[sessionId] = closed
            encoded[sessionId] = TrackCodec.encode(s.startedAt, points.remove(sessionId).orEmpty().sortedBy { it.t })
            return closed
        }
        override suspend fun findExpired(now: Long, inactiveSince: Long) = sessions.values.filter {
            it.status == LiveTrackStatus.ACTIVE && (it.deadlineAt < now || (it.lastPointAt ?: it.startedAt) < inactiveSince)
        }
        override suspend fun loadRecent(closedAfter: Long) = emptyList<Pair<LiveTrackSession, List<TrackPoint>>>()
        override suspend fun listByDistance(distanceId: Long) =
            sessions.values.filter { it.distanceId == distanceId }.map { ArchivedSession(it, encoded[it.id]) }
        override suspend fun distanceSummary(competitionId: String) = emptyList<TrackedDistanceResponse>()
    }

    private class FakeDirectory(var context: ParticipantContext?) : ParticipantDirectory {
        var extraIdsForUser = emptyList<String>()
        override suspend fun find(participantId: String) = context?.takeIf { it.participantId == participantId }
        override suspend fun findIdsByUser(competitionId: String, userId: String) =
            listOfNotNull(context?.takeIf { it.competitionId == competitionId && it.userId == userId }?.participantId) + extraIdsForUser
    }

    private val participant = ParticipantContext(
        participantId = "p1", userId = "u1", competitionId = "c1", displayName = "Иванов Иван",
        groupName = "М21", startNumber = 101, distanceId = 7, distanceName = "Длинная",
        plannedStartTime = NOW + TimeUnit.MINUTES.toMillis(10), competitionStartDate = NOW - TimeUnit.HOURS.toMillis(1),
        competitionEndDate = null, competitionStatus = "IN_PROGRESS", controlTimeMinutes = 90, hasFinalResult = false
    )

    private var now = NOW
    private val repository = FakeRepository()
    private val directory = FakeDirectory(participant)
    private val store = LiveTrackStore(epoch = "e")
    private val service = LiveTrackService(repository, directory, store) { now }

    private val startRequest = StartSessionRequest(competitionId = "c1", participantId = "p1", consent = true)

    private fun batch(seq: Int, vararg ts: Long) = PointsBatchRequest(seq, ts.map { PointRequest(it, 55.0, 37.0, 5f) })

    @Test
    fun `operations before initialization answer 503`() {
        assertFailsWith<ServiceUnavailableException> { runBlocking { service.start("u1", startRequest) } }
    }

    @Test
    fun `start computes deadline from planned start plus control time and grace`() = runBlocking<Unit> {
        service.initialize()

        val response = service.start("u1", startRequest)

        val expected = participant.plannedStartTime!! + TimeUnit.MINUTES.toMillis(90 + 60)
        assertEquals(expected, response.deadlineAt)
        assertEquals(LiveTrackStatus.ACTIVE, response.status)
    }

    @Test
    fun `second start of the same participant resumes the active session`() = runBlocking<Unit> {
        service.initialize()
        val first = service.start("u1", startRequest)
        service.appendPoints("u1", first.sessionId, batch(1, NOW))

        val second = service.start("u1", startRequest)

        assertEquals(first.sessionId, second.sessionId)
        assertEquals(1, second.lastBatchSeq)
    }

    @Test
    fun `start is rejected for foreign participant, without consent, outside the day, after result`() = runBlocking<Unit> {
        service.initialize()
        assertFailsWith<ForbiddenException> { service.start("someone-else", startRequest) }
        assertFailsWith<UnprocessableEntityException> { service.start("u1", startRequest.copy(consent = false)) }

        directory.context = participant.copy(competitionStartDate = NOW + TimeUnit.DAYS.toMillis(3))
        assertFailsWith<UnprocessableEntityException> { service.start("u1", startRequest) }

        directory.context = participant.copy(hasFinalResult = true)
        assertFailsWith<UnprocessableEntityException> { service.start("u1", startRequest) }
    }

    @Test
    fun `repeated batch is acknowledged without storing points twice`() = runBlocking<Unit> {
        service.initialize()
        val sessionId = service.start("u1", startRequest).sessionId

        service.appendPoints("u1", sessionId, batch(1, NOW + 1_000, NOW + 2_000))
        val repeated = service.appendPoints("u1", sessionId, batch(1, NOW + 1_000, NOW + 2_000))

        assertEquals(1, repeated.ackedBatchSeq)
        assertEquals(2, repository.points.getValue(sessionId).size)
        assertEquals(2, service.live(7, null).sessions.single().points.size)
    }

    @Test
    fun `points outside time window or coordinates are dropped, batch still accepted`() = runBlocking<Unit> {
        service.initialize()
        val sessionId = service.start("u1", startRequest).sessionId

        val ack = service.appendPoints(
            "u1", sessionId,
            PointsBatchRequest(1, listOf(
                PointRequest(NOW + 1_000, 55.0, 37.0, null),
                PointRequest(NOW - TimeUnit.HOURS.toMillis(1), 55.0, 37.0, null),
                PointRequest(NOW + TimeUnit.HOURS.toMillis(1), 55.0, 37.0, null),
                PointRequest(NOW + 2_000, 95.0, 37.0, null)
            ))
        )

        assertEquals(LiveTrackStatus.ACTIVE, ack.status)
        assertEquals(1, repository.points.getValue(sessionId).size)
    }

    @Test
    fun `final result closes the session and the client learns it from the next batch`() = runBlocking<Unit> {
        service.initialize()
        val sessionId = service.start("u1", startRequest).sessionId
        service.appendPoints("u1", sessionId, batch(1, NOW + 1_000))

        service.onResultSaved(ResultSavedEvent("c1", "p1", "STARTED", null, NOW))
        assertEquals(LiveTrackStatus.ACTIVE, repository.sessions.getValue(sessionId).status)

        service.onResultSaved(ResultSavedEvent("c1", "p1", "FINISHED", NOW + 5_000, NOW + 5_000))
        val ack = service.appendPoints("u1", sessionId, batch(2, NOW + 2_000))

        assertEquals(LiveTrackStatus.FINISHED, ack.status)
        assertEquals(CloseReason.RESULT_SAVED, ack.closeReason)
        assertNotNull(repository.encoded[sessionId])
        assertEquals(LiveTrackStatus.FINISHED, service.live(7, null).sessions.single().status)
    }

    @Test
    fun `sweep closes sessions past deadline and inactive ones`() = runBlocking<Unit> {
        service.initialize()
        val sessionId = service.start("u1", startRequest).sessionId

        now = NOW + TimeUnit.MINUTES.toMillis(50)
        assertEquals(1, service.sweep())
        assertEquals(CloseReason.INACTIVITY, repository.sessions.getValue(sessionId).closeReason)

        directory.context = participant.copy(participantId = "p2")
        val second = service.start("u1", startRequest.copy(participantId = "p2")).sessionId
        now += TimeUnit.HOURS.toMillis(3)
        service.appendPoints("u1", second, batch(1, now))
        assertEquals(1, service.sweep())
        assertEquals(CloseReason.CONTROL_TIME, repository.sessions.getValue(second).closeReason)
    }

    @Test
    fun `start without participantId finds the user's participant in the competition`() = runBlocking<Unit> {
        service.initialize()

        val response = service.start("u1", startRequest.copy(participantId = null))

        assertEquals("p1", repository.sessions.getValue(response.sessionId).participantId)
        assertFailsWith<UnprocessableEntityException> { service.start("stranger", startRequest.copy(participantId = null)) }
        directory.extraIdsForUser = listOf("p-other")
        assertFailsWith<UnprocessableEntityException> { service.start("u1", startRequest.copy(participantId = null)) }
    }

    @Test
    fun `points sent after the result closed the session are appended to the stored track`() = runBlocking<Unit> {
        service.initialize()
        val sessionId = service.start("u1", startRequest).sessionId
        service.appendPoints("u1", sessionId, batch(1, NOW + 1_000))
        now = NOW + 60_000
        service.onResultSaved(ResultSavedEvent("c1", "p1", "FINISHED", now, now))

        // Батч, снятый до финиша, досылается позже: дописывается; точка после закрытия отбрасывается.
        now = NOW + TimeUnit.MINUTES.toMillis(30)
        val ack = service.appendPoints("u1", sessionId, batch(2, NOW + 30_000, NOW + TimeUnit.MINUTES.toMillis(20)))

        assertEquals(LiveTrackStatus.FINISHED, ack.status)
        assertEquals(listOf(NOW + 1_000, NOW + 30_000), TrackCodec.decode(NOW, repository.encoded[sessionId]).map { it.t })
        assertEquals(2, service.live(7, null).sessions.single().points.size)

        // Повтор того же батча не задваивает, а после окна досылки точки не принимаются.
        service.appendPoints("u1", sessionId, batch(2, NOW + 30_000))
        now = NOW + TimeUnit.HOURS.toMillis(3)
        service.appendPoints("u1", sessionId, batch(3, NOW + 40_000))
        assertEquals(2, TrackCodec.decode(NOW, repository.encoded[sessionId]).size)
    }

    @Test
    fun `stop by owner closes, by others is forbidden`() = runBlocking<Unit> {
        service.initialize()
        val sessionId = service.start("u1", startRequest).sessionId

        assertFailsWith<ForbiddenException> { service.stop("intruder", sessionId) }
        val ack = service.stop("u1", sessionId)

        assertEquals(LiveTrackStatus.STOPPED, ack.status)
        assertTrue(service.tracks(7).single().trackEncoded.isEmpty())
    }
}
