package com.competra.data.services

import com.competra.domain.orienteering.ByChoiceMode
import com.competra.domain.orienteering.ranksByScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ByChoiceModeTest {

    @Test
    fun `score choice ranks by score`() {
        assertTrue(ranksByScore(direction = "BY_CHOICE", byChoiceMode = "SCORE"))
    }

    @Test
    fun `choice with minimum controls ranks by time`() {
        assertFalse(ranksByScore(direction = "BY_CHOICE", byChoiceMode = "MIN_CONTROLS"))
    }

    @Test
    fun `choice without stored mode stays score-O`() {
        assertTrue(ranksByScore(direction = "BY_CHOICE", byChoiceMode = null))
    }

    @Test
    fun `forward course ranks by time whatever the mode`() {
        assertFalse(ranksByScore(direction = "FORWARD", byChoiceMode = "SCORE"))
    }

    @Test
    fun `unknown mode falls back to score`() {
        assertEquals(ByChoiceMode.SCORE, ByChoiceMode.fromString("SOMETHING_ELSE"))
    }
}
