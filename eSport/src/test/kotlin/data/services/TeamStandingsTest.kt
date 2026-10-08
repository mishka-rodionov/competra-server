package com.competra.data.services

import com.competra.domain.orienteering.TeamOverallScope
import com.competra.domain.orienteering.TeamScoring
import com.competra.domain.orienteering.TeamScoringMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamStandingsTest {

    private val m16 = TeamStandingGroup(1, "М16", "M")
    private val m21 = TeamStandingGroup(2, "М21", "MALE")
    private val w21 = TeamStandingGroup(3, "Ж21", "F")

    private var nextId = 0

    private fun finished(group: TeamStandingGroup, team: String?, rank: Int, minutes: Long) = TeamStandingEntry(
        participantId = "p${nextId++}", firstName = "Имя", lastName = "Фамилия", groupId = group.groupId,
        commandName = team, status = "FINISHED", rank = rank, totalTime = minutes * 60, penaltyTime = 0
    )

    private fun dsq(group: TeamStandingGroup, team: String) = TeamStandingEntry(
        participantId = "p${nextId++}", firstName = "Имя", lastName = "Фамилия", groupId = group.groupId,
        commandName = team, status = "DSQ", rank = null, totalTime = null, penaltyTime = 0
    )

    private fun points(n: Int = 2, scopes: List<TeamOverallScope> = emptyList()) =
        TeamScoring(TeamScoringMethod.POINTS, n, scopes)

    @Test
    fun `points - sum of N best place points, extra results not counted`() {
        val entries = listOf(
            finished(m16, "Азимут", 1, 30), finished(m16, "Азимут", 3, 35), finished(m16, "Азимут", 5, 40),
            finished(m16, "Компас", 2, 32),
        )

        val group = computeTeamStandings(points(n = 2), listOf(m16), entries).groupStandings.single()

        assertEquals(listOf("Азимут" to 160, "Компас" to 80), group.teams.map { it.teamName to it.points })
        assertEquals(listOf(1, 2), group.teams.map { it.place })
        assertEquals(listOf(true, true, false), group.teams.first().members.map { it.counted })
    }

    @Test
    fun `team is the normalized caption, participants without caption do not count`() {
        val entries = listOf(
            finished(m16, " азимут ", 1, 30), finished(m16, "АЗИМУТ", 2, 31), finished(m16, null, 3, 32),
        )

        val teams = computeTeamStandings(points(), listOf(m16), entries).groupStandings.single().teams

        assertEquals(1, teams.size)
        assertEquals(180, teams.single().points)
    }

    @Test
    fun `points - equal sums are split by the best result, fully equal share the place`() {
        val entries = listOf(
            finished(m16, "А", 1, 30), finished(m16, "А", 40, 90), // 100 + 1
            finished(m16, "Б", 2, 31), finished(m16, "Б", 20, 60), // 80 + 21
            finished(m16, "В", 2, 31), finished(m16, "В", 20, 60), // 80 + 21
        )

        val teams = computeTeamStandings(points(), listOf(m16), entries).groupStandings.single().teams

        assertEquals(listOf("А" to 1, "Б" to 2, "В" to 2), teams.map { it.teamName to it.place })
    }

    @Test
    fun `points - team without any place is out of the standing`() {
        val entries = listOf(finished(m16, "А", 1, 30), dsq(m16, "Б"))

        val teams = computeTeamStandings(points(), listOf(m16), entries).groupStandings.single().teams

        assertEquals("Б", teams.last().teamName)
        assertNull(teams.last().place)
    }

    @Test
    fun `time - sum of N best times, fewer finishers is out of the standing`() {
        val settings = TeamScoring(TeamScoringMethod.TIME, 2)
        val entries = listOf(
            finished(m21, "Азимут", 3, 50), finished(m21, "Азимут", 4, 55), finished(m21, "Азимут", 9, 70),
            finished(m21, "Компас", 1, 40), finished(m21, "Компас", 2, 45),
            finished(m21, "Ориент", 5, 60), dsq(m21, "Ориент"),
        )

        val teams = computeTeamStandings(settings, listOf(m21), entries).groupStandings.single().teams

        assertEquals(
            listOf(Triple("Компас", 1, 85L * 60), Triple("Азимут", 2, 105L * 60)),
            teams.take(2).map { Triple(it.teamName, it.place, it.timeSeconds) }
        )
        assertEquals("Ориент", teams.last().teamName)
        assertNull(teams.last().place)
        assertNull(teams.last().timeSeconds)
    }

    @Test
    fun `overall - sums points for team places in groups of the scope`() {
        val settings = points(n = 1, scopes = listOf(TeamOverallScope.MEN, TeamOverallScope.WOMEN, TeamOverallScope.ALL))
        val entries = listOf(
            finished(m16, "Азимут", 1, 30), finished(m16, "Компас", 2, 31), // М16: Азимут 1, Компас 2
            finished(m21, "Компас", 1, 40), finished(m21, "Азимут", 2, 41), // М21: Компас 1, Азимут 2
            finished(w21, "Азимут", 1, 50),                                  // Ж21: Азимут 1
        )

        val overall = computeTeamStandings(settings, listOf(m16, m21, w21), entries).overallStandings
            .associateBy { it.scope }

        // Среди мужчин: Азимут 100 + 80 = 180, Компас 80 + 100 = 180 → равные слагаемые, делят 1-е место.
        assertEquals(listOf(1, 1), overall.getValue("MEN").teams.map { it.place })
        assertEquals(listOf(180, 180), overall.getValue("MEN").teams.map { it.points })
        assertEquals(listOf("Азимут" to 100), overall.getValue("WOMEN").teams.map { it.teamName to it.points })
        assertEquals(listOf("Азимут" to 280, "Компас" to 180), overall.getValue("ALL").teams.map { it.teamName to it.points })
    }

    @Test
    fun `overall - team out of a time group earns nothing there`() {
        val settings = TeamScoring(TeamScoringMethod.TIME, 2, listOf(TeamOverallScope.ALL))
        val entries = listOf(
            finished(m16, "Азимут", 1, 30), finished(m16, "Азимут", 2, 31),
            finished(m21, "Азимут", 1, 40), // в М21 у Азимута один финишировавший — вне зачёта
            finished(m21, "Компас", 2, 41), finished(m21, "Компас", 3, 42),
        )

        val all = computeTeamStandings(settings, listOf(m16, m21), entries).overallStandings.single()

        assertEquals(listOf("Азимут" to 100, "Компас" to 100), all.teams.map { it.teamName to it.points })
        assertTrue(all.teams.first().groups.single().groupTitle == "М16")
    }

    @Test
    fun `stored settings survive a round trip and unknown values fall back`() {
        val json = TeamScoringJson.write(TeamScoring(TeamScoringMethod.TIME, 4, listOf(TeamOverallScope.MEN)))

        assertEquals(TeamScoring(TeamScoringMethod.TIME, 4, listOf(TeamOverallScope.MEN)), TeamScoringJson.parse(json))
        assertEquals(
            TeamScoring(TeamScoringMethod.POINTS, 3, emptyList()),
            TeamScoringJson.parse("""{"groupMethod":"X","groupCountedResults":0,"overallScopes":["Y"]}""")
        )
        assertNull(TeamScoringJson.parse("not json"))
    }
}
