package com.competra.data.services

import com.competra.data.response.orienteering.GroupTeamResponse
import com.competra.data.response.orienteering.GroupTeamStandingResponse
import com.competra.data.response.orienteering.OverallTeamGroupResponse
import com.competra.data.response.orienteering.OverallTeamResponse
import com.competra.data.response.orienteering.OverallTeamStandingResponse
import com.competra.data.response.orienteering.TeamMemberResultResponse
import com.competra.data.response.orienteering.TeamStandingsResponse
import com.competra.data.util.RatingPointsTable
import com.competra.domain.orienteering.TeamOverallScope
import com.competra.domain.orienteering.TeamScoring
import com.competra.domain.orienteering.TeamScoringMethod

/** Группа соревнования для командного зачёта. Пол — "M"/"F" (web) или "MALE"/"FEMALE" (Android). */
internal data class TeamStandingGroup(val groupId: Long, val title: String, val gender: String?)

/** Результат участника для командного зачёта. Время и штраф — в секундах. */
internal data class TeamStandingEntry(
    val participantId: String,
    val firstName: String,
    val lastName: String,
    val groupId: Long,
    val commandName: String?,
    val status: String,
    val rank: Int?,
    val totalTime: Long?,
    val penaltyTime: Long
)

/**
 * Командный зачёт (docs/specs/team-scoring.md в competra-android). Чистая функция над результатами —
 * зачёт не хранится. Та же логика — в Android (`:domain`, офлайн-подсчёт в Центре).
 *
 * - Команда — нормализованная подпись (регистр и пробелы не важны); без подписи — не участвует.
 * - В группе: POINTS — сумма очков за места N лучших (RatingPointsTable); TIME — сумма времени N
 *   лучших, финишировавших меньше N — вне зачёта.
 * - Общие зачёты: сумма баллов за командные места в группах по той же таблице.
 */
internal fun computeTeamStandings(
    settings: TeamScoring,
    groups: List<TeamStandingGroup>,
    entries: List<TeamStandingEntry>
): TeamStandingsResponse {
    val counted = settings.groupCountedResults.coerceAtLeast(1)
    val entriesByGroup = entries
        .filter { normalizeCommandName(it.commandName) != null }
        .groupBy { it.groupId }

    val groupStandings = groups.mapNotNull { group ->
        val groupEntries = entriesByGroup[group.groupId].orEmpty()
        if (groupEntries.isEmpty()) return@mapNotNull null
        val teams = when (settings.groupMethod) {
            TeamScoringMethod.POINTS -> pointsStanding(groupEntries, counted)
            TeamScoringMethod.TIME -> timeStanding(groupEntries, counted)
        }
        GroupTeamStandingResponse(group.groupId, group.title, teams)
    }

    val overallStandings = TeamOverallScope.entries
        .filter { it in settings.overallScopes }
        .mapNotNull { scope ->
            val scopeGroupIds = groups.filter { it.belongsTo(scope) }.map { it.groupId }.toSet()
            val teams = overallStanding(groupStandings.filter { it.groupId in scopeGroupIds })
            teams.takeIf { it.isNotEmpty() }?.let { OverallTeamStandingResponse(scope.name, it) }
        }

    return TeamStandingsResponse(
        groupMethod = settings.groupMethod.name,
        groupCountedResults = counted,
        groupStandings = groupStandings,
        overallStandings = overallStandings
    )
}

private const val STATUS_FINISHED = "FINISHED"

private fun TeamStandingEntry.place(): Int? = rank?.takeIf { status == STATUS_FINISHED && it > 0 }

private fun TeamStandingEntry.points(): Int = place()?.let(RatingPointsTable::pointsForPlace) ?: 0

private fun TeamStandingEntry.raceSeconds(): Long? =
    totalTime?.takeIf { status == STATUS_FINISHED }?.let { it + penaltyTime }

private fun TeamStandingEntry.teamKey(): String = normalizeCommandName(commandName)!!.lowercase()

private fun TeamStandingEntry.toMember(isCounted: Boolean) = TeamMemberResultResponse(
    participantId = participantId,
    firstName = firstName,
    lastName = lastName,
    place = place(),
    status = status,
    points = points(),
    timeSeconds = raceSeconds(),
    counted = isCounted
)

private fun TeamStandingGroup.belongsTo(scope: TeamOverallScope): Boolean = when (scope) {
    TeamOverallScope.ALL -> true
    TeamOverallScope.MEN -> gender == "M" || gender == "MALE"
    TeamOverallScope.WOMEN -> gender == "F" || gender == "FEMALE"
}

/** Подпись команды для вывода — как у лучшего участника, без лишних пробелов. */
private fun List<TeamStandingEntry>.teamName(): String = normalizeCommandName(first().commandName)!!

/** Очки за места: N лучших по очкам, сумма. Команда без единого места — вне зачёта. */
private fun pointsStanding(entries: List<TeamStandingEntry>, counted: Int): List<GroupTeamResponse> {
    data class Scored(val name: String, val takenPoints: List<Int>, val members: List<TeamMemberResultResponse>)

    val scored = entries.groupBy { it.teamKey() }.values.map { teamEntries ->
        val ordered = teamEntries.sortedWith(
            compareByDescending<TeamStandingEntry> { it.points() }.thenBy { it.place() ?: Int.MAX_VALUE }
        )
        val taken = ordered.filter { it.place() != null }.take(counted)
        val members = taken.map { it.toMember(true) } + (ordered - taken.toSet()).map { it.toMember(false) }
        Scored(ordered.teamName(), taken.map { it.points() }, members)
    }
    val (ranked, outside) = scored.partition { it.takenPoints.isNotEmpty() }
    val placed = rankDescending(ranked) { it.takenPoints }
    return placed.map { (place, team) ->
        GroupTeamResponse(place, team.name, team.takenPoints.sum(), null, team.members)
    } + outside.sortedBy { it.name.lowercase() }.map { GroupTeamResponse(null, it.name, 0, null, it.members) }
}

/** Сумма времени: N лучших финишировавших; меньше N — вне зачёта. */
private fun timeStanding(entries: List<TeamStandingEntry>, counted: Int): List<GroupTeamResponse> {
    data class Timed(val name: String, val totalSeconds: Long?, val members: List<TeamMemberResultResponse>)

    val timed = entries.groupBy { it.teamKey() }.values.map { teamEntries ->
        val ordered = teamEntries.sortedWith(compareBy<TeamStandingEntry> { it.raceSeconds() ?: Long.MAX_VALUE })
        val finished = ordered.filter { it.raceSeconds() != null }
        val taken = if (finished.size >= counted) finished.take(counted) else emptyList()
        val members = taken.map { it.toMember(true) } + (ordered - taken.toSet()).map { it.toMember(false) }
        Timed(ordered.teamName(), taken.takeIf { it.isNotEmpty() }?.sumOf { it.raceSeconds()!! }, members)
    }
    val (ranked, outside) = timed.partition { it.totalSeconds != null }
    var place = 0
    var prevTotal: Long? = null
    val placed = ranked.sortedBy { it.totalSeconds }.mapIndexed { index, team ->
        if (team.totalSeconds != prevTotal) {
            place = index + 1
            prevTotal = team.totalSeconds
        }
        GroupTeamResponse(place, team.name, null, team.totalSeconds, team.members)
    }
    return placed + outside.sortedBy { it.name.lowercase() }.map { GroupTeamResponse(null, it.name, null, null, it.members) }
}

/** Общий зачёт: сумма баллов за командные места в группах (поделённое место — одинаковые баллы). */
private fun overallStanding(groupStandings: List<GroupTeamStandingResponse>): List<OverallTeamResponse> {
    data class Earned(val name: String, val groups: List<OverallTeamGroupResponse>)

    val byTeam = linkedMapOf<String, Earned>()
    groupStandings.forEach { standing ->
        standing.teams.forEach { team ->
            val place = team.place ?: return@forEach
            val key = team.teamName.lowercase()
            val earned = OverallTeamGroupResponse(standing.groupId, standing.groupTitle, place, RatingPointsTable.pointsForPlace(place))
            val current = byTeam[key]
            byTeam[key] = Earned(current?.name ?: team.teamName, current?.groups.orEmpty() + earned)
        }
    }
    val teams = byTeam.values.map { it.copy(groups = it.groups.sortedByDescending { g -> g.points }) }
    return rankDescending(teams) { team -> team.groups.map { it.points } }.map { (place, team) ->
        OverallTeamResponse(place, team.name, team.groups.sumOf { it.points }, team.groups)
    }
}

/**
 * Места по сумме убыв.; при равенстве — по слагаемым, сравниваемым по убыванию (у кого лучшее
 * слагаемое больше, затем второе…); полностью равные — делят место.
 */
private fun <T> rankDescending(items: List<T>, parts: (T) -> List<Int>): List<Pair<Int, T>> {
    val comparator = Comparator<List<Int>> { a, b ->
        val bySum = b.sum().compareTo(a.sum())
        if (bySum != 0) return@Comparator bySum
        val sortedA = a.sortedDescending()
        val sortedB = b.sortedDescending()
        for (i in 0 until maxOf(sortedA.size, sortedB.size)) {
            val cmp = (sortedB.getOrNull(i) ?: 0).compareTo(sortedA.getOrNull(i) ?: 0)
            if (cmp != 0) return@Comparator cmp
        }
        0
    }
    val sorted = items.sortedWith { x, y -> comparator.compare(parts(x), parts(y)) }
    var place = 0
    return sorted.mapIndexed { index, item ->
        if (index == 0 || comparator.compare(parts(sorted[index - 1]), parts(item)) != 0) place = index + 1
        place to item
    }
}
