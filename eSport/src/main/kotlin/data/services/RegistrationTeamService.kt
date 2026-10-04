package com.competra.data.services

import com.competra.data.database.entity.Competitions
import com.competra.data.database.entity.OrienteeringParticipants
import com.competra.data.database.entity.clubs.ClubMembers
import com.competra.data.database.entity.clubs.Clubs
import com.competra.data.database.entity.clubs.TeamMembers
import com.competra.data.database.entity.clubs.Teams
import com.competra.data.response.clubs.ClubMatchResponse
import com.competra.data.response.orienteering.RegistrationTeamOptionResponse
import com.competra.data.response.orienteering.RegistrationTeamOptionsResponse
import io.ktor.server.plugins.NotFoundException
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/** Подсказки для поля «Команда» при самостоятельной регистрации участника на соревнование. */
class RegistrationTeamService {

    /**
     * Клубные команды пользователя по виду спорта соревнования, подписи из протокола и подпись
     * для автоподстановки (см. [suggestCommandName]).
     */
    suspend fun getOptions(competitionId: String, userId: String): RegistrationTeamOptionsResponse = dbQuery {
        val competition = Competitions.selectAll()
            .where { Competitions.id eq competitionId }
            .singleOrNull() ?: throw NotFoundException("Соревнование не найдено")

        val options = userTeamOptions(userId, competition[Competitions.kindOfSport])

        val protocolNames = OrienteeringParticipants.select(OrienteeringParticipants.commandName)
            .where { OrienteeringParticipants.competitionId eq competitionId }
            .map { it[OrienteeringParticipants.commandName] }

        val last = OrienteeringParticipants.select(OrienteeringParticipants.teamId, OrienteeringParticipants.commandName)
            .where {
                (OrienteeringParticipants.userId eq userId) and
                    (OrienteeringParticipants.competitionId neq competitionId)
            }
            .orderBy(OrienteeringParticipants.updatedAt, SortOrder.DESC)
            .firstOrNull { normalizeCommandName(it[OrienteeringParticipants.commandName]) != null }

        RegistrationTeamOptionsResponse(
            options = options.map {
                RegistrationTeamOptionResponse(
                    teamId = it.teamId,
                    clubId = it.clubId,
                    clubName = it.clubName,
                    teamName = it.teamName,
                    label = it.label
                )
            },
            protocolNames = distinctProtocolNames(protocolNames, PROTOCOL_NAMES_LIMIT),
            suggestedCommandName = suggestCommandName(
                options = options,
                lastTeamId = last?.get(OrienteeringParticipants.teamId),
                lastCommandName = last?.get(OrienteeringParticipants.commandName)
            )
        )
    }

    /** Клубы с названием, совпадающим с подписью команды (без учёта регистра), где пользователь не состоит. */
    suspend fun matchClubs(commandName: String?, userId: String): List<ClubMatchResponse> = dbQuery {
        val candidates = clubNameCandidates(commandName)
        if (candidates.isEmpty()) return@dbQuery emptyList()

        val myClubIds = ClubMembers.select(ClubMembers.clubId)
            .where { ClubMembers.userId eq userId }
            .map { it[ClubMembers.clubId] }
            .toSet()

        Clubs.selectAll()
            .where { Clubs.name.lowerCase() inList candidates }
            .orderBy(Clubs.foundedAt, SortOrder.ASC)
            .filter { it[Clubs.id] !in myClubIds }
            .take(CLUB_MATCH_LIMIT)
            .map {
                ClubMatchResponse(
                    id = it[Clubs.id],
                    name = it[Clubs.name],
                    allowJoinRequests = it[Clubs.allowJoinRequests]
                )
            }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    private companion object {
        const val PROTOCOL_NAMES_LIMIT = 50
        const val CLUB_MATCH_LIMIT = 3
    }
}

/**
 * Клубные команды пользователя по виду спорта; клуб, где у пользователя нет подходящей команды, —
 * отдельным вариантом без teamId. Вызывать внутри транзакции.
 */
internal fun userTeamOptions(userId: String, sportType: String): List<TeamOption> {
    val memberships = ClubMembers
        .join(Clubs, JoinType.INNER, ClubMembers.clubId, Clubs.id)
        .select(ClubMembers.id, Clubs.id, Clubs.name)
        .where { ClubMembers.userId eq userId }
        .associate { it[ClubMembers.id] to (it[Clubs.id] to it[Clubs.name]) }
    if (memberships.isEmpty()) return emptyList()

    val teamsByClub = TeamMembers
        .join(Teams, JoinType.INNER, TeamMembers.teamId, Teams.id)
        .select(TeamMembers.clubMemberId, Teams.id, Teams.name)
        .where { (TeamMembers.clubMemberId inList memberships.keys) and (Teams.sportType eq sportType) }
        .groupBy(
            keySelector = { memberships.getValue(it[TeamMembers.clubMemberId]).first },
            valueTransform = { it[Teams.id] to it[Teams.name] }
        )

    return memberships.values
        .sortedBy { (_, clubName) -> clubName.lowercase() }
        .flatMap { (clubId, clubName) ->
            val teams = teamsByClub[clubId].orEmpty().sortedBy { (_, teamName) -> teamName.lowercase() }
            if (teams.isEmpty()) {
                listOf(TeamOption(teamId = null, clubId = clubId, clubName = clubName, teamName = null))
            } else {
                teams.map { (teamId, teamName) ->
                    TeamOption(teamId = teamId, clubId = clubId, clubName = clubName, teamName = teamName)
                }
            }
        }
}

/** Подпись команды [teamId] для протокола, если пользователь в ней состоит, иначе null. Вызывать внутри транзакции. */
internal fun memberTeamLabel(teamId: String, userId: String): String? =
    TeamMembers
        .join(ClubMembers, JoinType.INNER, TeamMembers.clubMemberId, ClubMembers.id)
        .join(Teams, JoinType.INNER, TeamMembers.teamId, Teams.id)
        .join(Clubs, JoinType.INNER, Teams.clubId, Clubs.id)
        .select(Clubs.name, Teams.name)
        .where { (TeamMembers.teamId eq teamId) and (ClubMembers.userId eq userId) }
        .singleOrNull()
        ?.let { teamLabel(it[Clubs.name], it[Teams.name]) }
