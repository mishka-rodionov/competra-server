package com.competra.data.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NameMatchingTest {

    private fun match(pFirst: String, pLast: String, middle: String? = null) =
        namesMatch("Пётр", "Семёнов", middle, pFirst, pLast)

    @Test
    fun `normalizes case, yo and whitespace`() {
        assertEquals("семенов петр", normalizePersonName("  СЕМЁНОВ   Пётр "))
    }

    @Test
    fun `matches exact name ignoring case and yo`() {
        assertTrue(match("петр", "СЕМЕНОВ"))
    }

    @Test
    fun `matches swapped first and last name`() {
        assertTrue(match("Семенов", "Петр"))
    }

    @Test
    fun `matches full name typed into one field`() {
        assertTrue(match("", "Семенов Петр"))
        assertTrue(match("Семенов Петр", ""))
    }

    @Test
    fun `matches name with middle name when profile has it`() {
        assertTrue(match("Петр Иванович", "Семенов", middle = "Иванович"))
        assertFalse(match("Петр Иванович", "Семенов", middle = null))
    }

    @Test
    fun `keeps double last names intact`() {
        assertTrue(namesMatch("Анна", "Иванова-Петрова", null, "Анна", "Иванова-Петрова"))
        assertFalse(namesMatch("Анна", "Иванова-Петрова", null, "Анна", "Иванова"))
    }

    @Test
    fun `does not match different people or empty profile`() {
        assertFalse(match("Павел", "Семенов"))
        assertFalse(namesMatch("", "", null, "", ""))
    }

    @Test
    fun `registration name from profile matches old profile name ignoring case and yo`() {
        assertTrue(isRegistrationNameFromProfile("Пётр", "Семёнов", " петр ", "СЕМЕНОВ"))
    }

    @Test
    fun `registration edited by organizer or swapped is not from profile`() {
        assertFalse(isRegistrationNameFromProfile("Пётр", "Семёнов", "Пётр", "Семенова"))
        assertFalse(isRegistrationNameFromProfile("Пётр", "Семёнов", "Семёнов", "Пётр"))
        assertFalse(isRegistrationNameFromProfile("", "", "", ""))
    }
}
