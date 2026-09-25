package com.competra.data.services

import com.competra.domain.user.Gender
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GroupEligibilityTest {

    private fun utcMidnight(date: String) =
        LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli().toString()

    private fun check(
        groupGender: String? = null,
        minAge: Int? = null,
        maxAge: Int? = null,
        userGender: Gender? = Gender.MALE,
        birthDate: String? = utcMidnight("2012-06-15"),
        year: Int = 2026,
    ) = checkGroupEligibility("М14", groupGender, minAge, maxAge, userGender, birthDate, year)

    @Test
    fun `group gender accepts both web and android formats`() {
        assertEquals(Gender.MALE, groupGenderRestriction("M"))
        assertEquals(Gender.MALE, groupGenderRestriction("MALE"))
        assertEquals(Gender.FEMALE, groupGenderRestriction("F"))
        assertEquals(Gender.FEMALE, groupGenderRestriction("female"))
        assertNull(groupGenderRestriction("MIXED"))
        assertNull(groupGenderRestriction(""))
        assertNull(groupGenderRestriction(null))
    }

    @Test
    fun `birth year is parsed from utc and local midnight millis`() {
        assertEquals(2012, parseBirthYear(utcMidnight("2012-01-01")))
        val moscowMidnight = LocalDate.parse("2012-01-01").atStartOfDay(ZoneId.of("Europe/Moscow"))
            .toInstant().toEpochMilli().toString()
        assertEquals(2012, parseBirthYear(moscowMidnight))
        assertEquals(2012, parseBirthYear("2012-12-31"))
        assertNull(parseBirthYear(""))
        assertNull(parseBirthYear("garbage"))
    }

    @Test
    fun `competition year uses competition time zone`() {
        // 31.12.2025 22:00 UTC — уже 1 января 2026 в Москве.
        val millis = LocalDate.parse("2025-12-31").atStartOfDay(ZoneOffset.UTC).plusHours(22).toInstant().toEpochMilli()
        assertEquals(2026, competitionYear(millis, "Europe/Moscow"))
        assertEquals(2025, competitionYear(millis, "UTC"))
        assertEquals(2025, competitionYear(millis, "not/a-zone"))
    }

    @Test
    fun `group without restrictions accepts anyone even with empty profile`() {
        assertNull(check(userGender = null, birthDate = ""))
        assertNull(check(groupGender = "MIXED", minAge = 0, maxAge = 0, userGender = null, birthDate = ""))
    }

    @Test
    fun `gender mismatch is rejected`() {
        assertNull(check(groupGender = "M"))
        assertEquals("Группа М14 — только для женщин", check(groupGender = "FEMALE"))
    }

    @Test
    fun `missing gender in profile is rejected for gendered group`() {
        assertNotNull(check(groupGender = "M", userGender = null))
    }

    @Test
    fun `age is counted by birth year`() {
        // Родился в декабре 2012 — в 2026 году ему «14», хотя на момент старта ещё 13.
        assertNull(check(minAge = 14, maxAge = 14, birthDate = utcMidnight("2012-12-31")))
        assertEquals(
            "Группа М14 — для участников 2011–2012 г.р., ваш год рождения — 2013",
            check(minAge = 14, maxAge = 15, birthDate = utcMidnight("2013-01-01")),
        )
    }

    @Test
    fun `open-ended age ranges`() {
        assertEquals(
            "Группа М14 — для участников 2012 г.р. и моложе, ваш год рождения — 2011",
            check(maxAge = 14, birthDate = utcMidnight("2011-05-05")),
        )
        assertEquals(
            "Группа М14 — для участников 1991 г.р. и старше, ваш год рождения — 2012",
            check(minAge = 35),
        )
    }

    @Test
    fun `missing birth date is rejected for age-limited group`() {
        assertEquals(
            "Укажите дату рождения в профиле, чтобы зарегистрироваться в группу М14",
            check(maxAge = 14, birthDate = ""),
        )
    }
}
