package com.competra.data.services

import com.competra.domain.orienteering.OvertimePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlTimeTest {

    /** КВ 90 минут в секундах: totalTime результата хранится в секундах. */
    private val ninetyMinutes = 90 * 60L

    @Test
    fun `group control time overrides competition default`() {
        assertEquals(
            60,
            ResultRanking.effectiveControlTimeMinutes(groupLimitMinutes = 60, competitionLimitMinutes = 90)
        )
    }

    @Test
    fun `group without own control time inherits competition default`() {
        assertEquals(
            90,
            ResultRanking.effectiveControlTimeMinutes(groupLimitMinutes = null, competitionLimitMinutes = 90)
        )
    }

    @Test
    fun `control time is absent when neither level defines it`() {
        assertNull(
            ResultRanking.effectiveControlTimeMinutes(groupLimitMinutes = null, competitionLimitMinutes = null)
        )
    }

    @Test
    fun `result slower than control time is overtime`() {
        assertTrue(ResultRanking.isOvertime(totalTimeSeconds = ninetyMinutes + 1, limitSeconds = ninetyMinutes))
    }

    @Test
    fun `result exactly at control time fits the limit`() {
        assertFalse(ResultRanking.isOvertime(totalTimeSeconds = ninetyMinutes, limitSeconds = ninetyMinutes))
    }

    @Test
    fun `result without total time is never overtime`() {
        assertFalse(ResultRanking.isOvertime(totalTimeSeconds = null, limitSeconds = ninetyMinutes))
    }

    @Test
    fun `no control time means no overtime`() {
        assertFalse(ResultRanking.isOvertime(totalTimeSeconds = ninetyMinutes * 10, limitSeconds = null))
    }

    @Test
    fun `unknown policy falls back to ignore`() {
        assertEquals(OvertimePolicy.IGNORE, OvertimePolicy.fromString("SOMETHING_ELSE"))
        assertEquals(OvertimePolicy.IGNORE, OvertimePolicy.fromString(null))
    }

    @Test
    fun `known policies are parsed`() {
        assertEquals(OvertimePolicy.DISQUALIFY, OvertimePolicy.fromString("DISQUALIFY"))
        assertEquals(OvertimePolicy.SCORE_PENALTY, OvertimePolicy.fromString("SCORE_PENALTY"))
    }
}
