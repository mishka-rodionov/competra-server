package com.competra.data.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistrationTeamTest {

    private fun team(id: String, club: String, name: String) =
        TeamOption(teamId = id, clubId = "c-$club", clubName = club, teamName = name)

    private fun club(club: String) =
        TeamOption(teamId = null, clubId = "c-$club", clubName = club, teamName = null)

    @Test
    fun `normalize trims and collapses whitespace`() {
        assertEquals("СК Азимут", normalizeCommandName("  СК   Азимут "))
        assertNull(normalizeCommandName("   "))
        assertNull(normalizeCommandName(null))
    }

    @Test
    fun `same command name ignores case and whitespace`() {
        assertTrue(sameCommandName("Лесные  лисы", "лесные лисы "))
        assertTrue(sameCommandName(null, " "))
        assertFalse(sameCommandName("Азимут", "Азимут (Юниоры)"))
    }

    @Test
    fun `label joins club and team unless names match`() {
        assertEquals("СК Азимут (Юниоры)", teamLabel("СК Азимут", "Юниоры"))
        assertEquals("СК Азимут", teamLabel("СК Азимут", "ск азимут"))
        assertEquals("СК Азимут", teamLabel("СК Азимут", null))
    }

    @Test
    fun `protocol names are deduplicated, most frequent first, with most frequent spelling`() {
        val names = listOf("Азимут", "азимут", "Азимут ", "Буссоль", null, "", "Буссоль", "Компас")
        assertEquals(listOf("Азимут", "Буссоль", "Компас"), distinctProtocolNames(names, limit = 10))
        assertEquals(listOf("Азимут"), distinctProtocolNames(names, limit = 1))
    }

    @Test
    fun `club candidates include club part of label`() {
        assertEquals(listOf("ск азимут (юниоры)", "ск азимут"), clubNameCandidates("СК Азимут (Юниоры)"))
        assertEquals(listOf("азимут"), clubNameCandidates(" Азимут "))
        assertEquals(emptyList(), clubNameCandidates(" "))
    }

    @Test
    fun `suggest prefers last used team`() {
        val options = listOf(team("t1", "Азимут", "Юниоры"), team("t2", "Азимут", "Взрослые"))
        assertEquals("Азимут (Взрослые)", suggestCommandName(options, lastTeamId = "t2", lastCommandName = "x"))
    }

    @Test
    fun `suggest matches last label when team id is gone`() {
        val options = listOf(club("Азимут"), club("Буссоль"))
        assertEquals("Буссоль", suggestCommandName(options, lastTeamId = null, lastCommandName = "буссоль"))
    }

    @Test
    fun `suggest single team`() {
        val options = listOf(team("t1", "Азимут", "Юниоры"), club("Буссоль"))
        assertEquals("Азимут (Юниоры)", suggestCommandName(options, lastTeamId = null, lastCommandName = null))
    }

    @Test
    fun `suggest single club without teams`() {
        assertEquals("Азимут", suggestCommandName(listOf(club("Азимут")), null, null))
    }

    @Test
    fun `no suggestion when choice is ambiguous`() {
        val options = listOf(team("t1", "Азимут", "Юниоры"), team("t2", "Буссоль", "Взрослые"))
        assertNull(suggestCommandName(options, lastTeamId = null, lastCommandName = "Сборная"))
        assertNull(suggestCommandName(listOf(club("Азимут"), club("Буссоль")), null, null))
    }

    @Test
    fun `without clubs suggest last free text`() {
        assertEquals("Лесные лисы", suggestCommandName(emptyList(), null, " Лесные  лисы "))
        assertNull(suggestCommandName(emptyList(), null, null))
    }
}
