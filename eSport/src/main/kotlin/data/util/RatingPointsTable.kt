package com.competra.data.util

/**
 * Фиксированная таблица очков по занятому месту для MVP рейтинга соревнований,
 * основана на официальной таблице очков IOF World Cup.
 */
object RatingPointsTable {

    private val fixedPoints = mapOf(
        1 to 100, 2 to 80, 3 to 60, 4 to 50, 5 to 45,
        6 to 40, 7 to 37, 8 to 35, 9 to 33
    )

    /** Места 10..40 — по формуле -place+41 (10=31 ... 40=1). Ниже 40-го места — 0 очков. */
    fun pointsForPlace(place: Int): Int = when {
        place < 1 -> 0
        place in fixedPoints -> fixedPoints.getValue(place)
        place in 10..40 -> -place + 41
        else -> 0
    }
}
