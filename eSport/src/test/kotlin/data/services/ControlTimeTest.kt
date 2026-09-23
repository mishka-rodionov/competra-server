package com.competra.data.services

import com.competra.domain.orienteering.OvertimePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlTimeTest {

    private val ninetyMinutes = 90 * 60_000L

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
        assertTrue(ResultRanking.isOvertime(totalTime = ninetyMinutes + 1, limitMillis = ninetyMinutes))
    }

    @Test
    fun `result exactly at control time fits the limit`() {
        assertFalse(ResultRanking.isOvertime(totalTime = ninetyMinutes, limitMillis = ninetyMinutes))
    }

    @Test
    fun `result without total time is never overtime`() {
        assertFalse(ResultRanking.isOvertime(totalTime = null, limitMillis = ninetyMinutes))
    }

    @Test
    fun `no control time means no overtime`() {
        assertFalse(ResultRanking.isOvertime(totalTime = ninetyMinutes * 10, limitMillis = null))
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
