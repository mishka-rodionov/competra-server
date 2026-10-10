package com.competra.data.services

import com.competra.data.database.entity.OrienteeringCompetitions
import com.competra.data.database.entity.OrienteeringParticipants
import com.competra.data.database.entity.OrienteeringResults
import com.competra.data.database.entity.ParticipantGroups
import com.competra.data.response.orienteering.TeamStandingsResponse
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.competra.data.database.ioTransaction

/**
 * Командный зачёт соревнования — вычисляется при каждом запросе из результатов
 * ([computeTeamStandings]), ничего не хранит. Доступен тем же, кому и результаты (публично).
 */
class TeamStandingsService {

    /** null — соревнования нет или командный зачёт в нём не включён. */
    suspend fun getByCompetition(competitionId: String): TeamStandingsResponse? = dbQuery {
        val settings = OrienteeringCompetitions.selectAll()
            .where { OrienteeringCompetitions.id eq competitionId }
            .singleOrNull()
            ?.let { TeamScoringJson.parse(it[OrienteeringCompetitions.teamScoring]) }
            ?: return@dbQuery null

        // Порядок групп — как в деталях соревнования (мужские → женские → остальные, по возрасту).
        val groups = ParticipantGroups.selectAll()
            .where { ParticipantGroups.competitionId eq competitionId }
            .map {
                TeamStandingGroup(
                    groupId = it[ParticipantGroups.id],
                    title = it[ParticipantGroups.title],
                    gender = it[ParticipantGroups.gender],
                    countedResults = it[ParticipantGroups.teamCountedResults]
                )
            }
            .sortedWith(
                compareBy<TeamStandingGroup> { participantGroupSortPriority(it.title, it.gender) }
                    .thenBy(nullsLast()) { extractAgeFromTitle(it.title) }
            )

        val participants = OrienteeringParticipants.selectAll()
            .where { OrienteeringParticipants.competitionId eq competitionId }
            .associateBy { it[OrienteeringParticipants.id] }

        val entries = OrienteeringResults.selectAll()
            .where { OrienteeringResults.competitionId eq competitionId }
            .mapNotNull { row ->
                val participant = participants[row[OrienteeringResults.participantId]] ?: return@mapNotNull null
                TeamStandingEntry(
                    participantId = participant[OrienteeringParticipants.id],
                    firstName = participant[OrienteeringParticipants.firstName],
                    lastName = participant[OrienteeringParticipants.lastName],
                    groupId = row[OrienteeringResults.groupId],
                    commandName = participant[OrienteeringParticipants.commandName],
                    status = row[OrienteeringResults.status],
                    rank = row[OrienteeringResults.rank],
                    totalTime = row[OrienteeringResults.totalTime],
                    penaltyTime = row[OrienteeringResults.penaltyTime]
                )
            }

        computeTeamStandings(settings, groups, entries)
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        ioTransaction { block() }
}
