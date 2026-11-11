package com.competra.data.services

import com.competra.domain.orienteering.CourseFormat
import com.competra.domain.orienteering.INHERIT_FORMAT
import com.competra.domain.orienteering.OvertimePolicies
import com.competra.domain.orienteering.OvertimePolicy
import com.competra.domain.orienteering.RequestedValue
import com.competra.domain.orienteering.effectiveCourseFormat
import com.competra.domain.orienteering.requestedDistanceDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CourseFormatTest {

    @Test
    fun `distance without own format inherits competition`() {
        val format = effectiveCourseFormat(null, null, "BY_CHOICE", "MIN_CONTROLS")
        assertEquals(CourseFormat("BY_CHOICE", "MIN_CONTROLS"), format)
    }

    @Test
    fun `distance format overrides competition`() {
        val format = effectiveCourseFormat("BY_CHOICE", "SCORE", "FORWARD", "SCORE")
        assertTrue(format.ranksByScore)
    }

    @Test
    fun `by choice distance without mode takes competition mode`() {
        val format = effectiveCourseFormat("BY_CHOICE", null, "FORWARD", "MIN_CONTROLS")
        assertFalse(format.ranksByScore)
    }

    @Test
    fun `mode is ignored when comparing non choice formats`() {
        assertTrue(CourseFormat("FORWARD", "SCORE").sameAs(CourseFormat("FORWARD", "MIN_CONTROLS")))
        assertFalse(CourseFormat("BY_CHOICE", "SCORE").sameAs(CourseFormat("BY_CHOICE", "MIN_CONTROLS")))
    }

    @Test
    fun `request direction null keeps and inherit clears`() {
        assertEquals(RequestedValue.Keep, requestedDistanceDirection(null))
        assertEquals(RequestedValue.Set(null), requestedDistanceDirection(INHERIT_FORMAT))
        assertEquals(RequestedValue.Set("MARKING"), requestedDistanceDirection("MARKING"))
    }

    @Test
    fun `legacy score competition keeps its penalty and gets reference control time for time courses`() {
        val policies = OvertimePolicies.resolve("SCORE_PENALTY", null, null, competitionRanksByScore = true)
        assertEquals(OvertimePolicies(time = OvertimePolicy.IGNORE, score = OvertimePolicy.SCORE_PENALTY), policies)
    }

    @Test
    fun `legacy time competition keeps its policy and gets score penalty for score courses`() {
        val policies = OvertimePolicies.resolve("DISQUALIFY", null, null, competitionRanksByScore = false)
        assertEquals(OvertimePolicies(time = OvertimePolicy.DISQUALIFY, score = OvertimePolicy.SCORE_PENALTY), policies)
    }

    @Test
    fun `explicit policies win over legacy`() {
        val policies = OvertimePolicies.resolve("IGNORE", "DISQUALIFY", "IGNORE", competitionRanksByScore = false)
        assertEquals(OvertimePolicy.DISQUALIFY, policies.forCourse(ranksByScore = false))
        assertEquals(OvertimePolicy.IGNORE, policies.forCourse(ranksByScore = true))
    }

    @Test
    fun `score penalty is not applied to time courses`() {
        val policies = OvertimePolicies.resolve(null, "SCORE_PENALTY", null, competitionRanksByScore = false)
        assertEquals(OvertimePolicy.IGNORE, policies.time)
    }

    @Test
    fun `legacy field follows default format kind`() {
        val policies = OvertimePolicies(time = OvertimePolicy.DISQUALIFY, score = OvertimePolicy.SCORE_PENALTY)
        assertEquals(OvertimePolicy.SCORE_PENALTY, policies.legacy(competitionRanksByScore = true))
        assertEquals(OvertimePolicy.DISQUALIFY, policies.legacy(competitionRanksByScore = false))
    }
}
