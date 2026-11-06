package com.competra.data.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandingDuplicatesTest {

    private fun guest(key: String, last: String, first: String) = StandingIdentity(key, null, first, last)
    private fun user(key: String, last: String, first: String) = StandingIdentity(key, "u-$key", first, last)

    @Test
    fun `initials and abbreviations are compatible`() {
        assertTrue(firstNamesCompatible("М.", "Михаил"))
        assertTrue(firstNamesCompatible("м", "Михаил"))
        assertTrue(firstNamesCompatible("Е.А.", "Елизавета"))
        assertTrue(firstNamesCompatible("Алекс.", "Алексей"))
        assertTrue(firstNamesCompatible("Пётр", "петр"))
        assertTrue(firstNamesCompatible("", "Мария"))
    }

    @Test
    fun `different full names are not compatible`() {
        assertFalse(firstNamesCompatible("Михаил", "Максим"))
        assertFalse(firstNamesCompatible("Мих", "Михаил"))
        assertFalse(firstNamesCompatible("А.", "Михаил"))
    }

    @Test
    fun `groups account with guest records of the same person`() {
        val groups = findDuplicateGroups(
            listOf(
                user("a", "Родионов", "Михаил"),
                guest("b", "Родионов", "М."),
                guest("c", "Изгарёв", "Егор"),
                guest("d", "Изгарев", "Егор"),
                guest("e", "Родионов", "Александр"),
            )
        )
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), groups)
    }

    @Test
    fun `two accounts alone are not a duplicate group`() {
        assertEquals(
            emptyList(),
            findDuplicateGroups(listOf(user("a", "Иванов", "Иван"), user("b", "Иванов", "Иван"))),
        )
    }
}
