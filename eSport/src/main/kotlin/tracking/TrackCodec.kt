package com.competra.tracking

import kotlin.math.roundToLong

/**
 * Компактная строка трека: точки через `;`, поля через `:` — `latE5:lonE5:tOffsetSec`
 * (координаты × 100 000, смещение в секундах от базового времени; может быть отрицательным
 * для точек, снятых до нажатия «старт»).
 *
 * Тот же формат, что у треков тренировок — копии в competra-android (`domain/diary/TrackCodec.kt`)
 * и competra-web-ts (`src/lib/trackCodec.ts`). При изменении формата править все копии.
 */
object TrackCodec {

    private const val POINT_SEPARATOR = ";"
    private const val FIELD_SEPARATOR = ":"
    private const val COORD_PRECISION = 100_000.0

    /** Кодирует точки (в переданном порядке) относительно [baseMs]. */
    fun encode(baseMs: Long, points: List<TrackPoint>): String =
        points.joinToString(POINT_SEPARATOR) { point ->
            val latE5 = (point.lat * COORD_PRECISION).roundToLong()
            val lonE5 = (point.lon * COORD_PRECISION).roundToLong()
            val tOffsetSec = (point.t - baseMs) / 1000L
            "$latE5$FIELD_SEPARATOR$lonE5$FIELD_SEPARATOR$tOffsetSec"
        }

    /** Декодирует строку; битые фрагменты пропускаются. */
    fun decode(baseMs: Long, encoded: String?): List<TrackPoint> {
        if (encoded.isNullOrBlank()) return emptyList()
        return encoded.split(POINT_SEPARATOR).mapNotNull { chunk ->
            val fields = chunk.split(FIELD_SEPARATOR)
            if (fields.size != 3) return@mapNotNull null
            val latE5 = fields[0].toLongOrNull() ?: return@mapNotNull null
            val lonE5 = fields[1].toLongOrNull() ?: return@mapNotNull null
            val tOffsetSec = fields[2].toLongOrNull() ?: return@mapNotNull null
            TrackPoint(t = baseMs + tOffsetSec * 1000L, lat = latE5 / COORD_PRECISION, lon = lonE5 / COORD_PRECISION)
        }
    }
}
