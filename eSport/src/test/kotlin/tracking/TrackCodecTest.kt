package com.competra.tracking

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackCodecTest {

    @Test
    fun `encodes in the format shared with Android and web, including negative offsets`() {
        val base = 1_790_000_000_000L
        val points = listOf(
            TrackPoint(t = base - 3_000, lat = 55.751234, lon = 37.618456),
            TrackPoint(t = base + 12_000, lat = -33.868, lon = 151.2093)
        )

        val encoded = TrackCodec.encode(base, points)

        assertEquals("5575123:3761846:-3;-3386800:15120930:12", encoded)
        assertEquals(
            listOf(TrackPoint(base - 3_000, 55.75123, 37.61846), TrackPoint(base + 12_000, -33.868, 151.2093)),
            TrackCodec.decode(base, encoded)
        )
    }

    @Test
    fun `decode skips malformed chunks`() {
        assertEquals(1, TrackCodec.decode(0, "1:2:3;garbage;4:5").size)
        assertEquals(emptyList(), TrackCodec.decode(0, null))
    }
}
