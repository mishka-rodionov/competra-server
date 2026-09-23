package com.competra.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTrackStoreTest {

    private fun session(id: String, distanceId: Long = 1) = LiveTrackSession(
        id = id, competitionId = "c", distanceId = distanceId, distanceName = "М21", participantId = "p-$id",
        userId = "u", displayName = "Иванов Иван", groupName = "М21", startNumber = 1,
        status = LiveTrackStatus.ACTIVE, closeReason = null, startedAt = 1_000, closedAt = null,
        lastPointAt = null, deadlineAt = 10_000_000, lastBatchSeq = 0, consentAt = 1_000
    )

    @Test
    fun `cursor returns only points added after it, in arrival order`() {
        val store = LiveTrackStore(epoch = "e1")
        store.addPoints(session("a"), listOf(TrackPoint(2_000, 1.0, 1.0)))
        val first = store.snapshot(1, null, serverTime = 5)

        // Досланная после потери связи точка «старше» уже показанной, но всё равно новая для курсора.
        store.addPoints(session("a"), listOf(TrackPoint(1_500, 2.0, 2.0)))
        val second = store.snapshot(1, first.cursor, serverTime = 6)

        assertEquals("e1:1", first.cursor)
        assertEquals(listOf(listOf<Number>(1_500L, 2.0, 2.0)), second.sessions.single().points)
        assertFalse(second.reset)
    }

    @Test
    fun `cursor from another process start resets to full sorted tracks`() {
        val store = LiveTrackStore(epoch = "new")
        store.addPoints(session("a"), listOf(TrackPoint(3_000, 1.0, 1.0), TrackPoint(2_000, 2.0, 2.0)))

        val snapshot = store.snapshot(1, "old:42", serverTime = 0)

        assertTrue(snapshot.reset)
        assertEquals(listOf(2_000L, 3_000L), snapshot.sessions.single().points.map { it[0] })
    }

    @Test
    fun `snapshot contains metadata of all sessions of the distance only`() {
        val store = LiveTrackStore(epoch = "e")
        store.upsertSession(session("a"))
        store.upsertSession(session("b", distanceId = 2))

        val snapshot = store.snapshot(1, "e:0", serverTime = 0)

        assertEquals(listOf("a"), snapshot.sessions.map { it.sessionId })
        assertTrue(snapshot.sessions.single().points.isEmpty())
    }

    @Test
    fun `evicts sessions closed before cutoff`() {
        val store = LiveTrackStore(epoch = "e")
        store.upsertSession(session("old").copy(status = LiveTrackStatus.FINISHED, closedAt = 100))
        store.upsertSession(session("fresh").copy(status = LiveTrackStatus.FINISHED, closedAt = 900))
        store.upsertSession(session("active"))

        store.evictClosedBefore(500)

        assertEquals(setOf("fresh", "active"), store.snapshot(1, null, 0).sessions.map { it.sessionId }.toSet())
    }
}
