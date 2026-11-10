package com.competra.data.services

import com.competra.domain.orienteering.competitionRanks
import kotlin.test.Test
import kotlin.test.assertEquals

class CompetitionRanksTest {

    @Test
    fun `equal results share a place and the next one skips it`() {
        assertEquals(listOf(1, 1, 3, 4, 4, 4, 7), competitionRanks(listOf(10, 10, 12, 15, 15, 15, 20)))
    }

    @Test
    fun `distinct results get consecutive places`() {
        assertEquals(listOf(1, 2, 3), competitionRanks(listOf(10, 11, 12)))
        assertEquals(emptyList(), competitionRanks(emptyList<Int>()))
    }
}
