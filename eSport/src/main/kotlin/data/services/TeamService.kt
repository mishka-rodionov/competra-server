package com.competra.data.services

import com.competra.data.database.entity.clubs.ClubMembers
import com.competra.data.database.entity.clubs.Teams
import com.competra.data.database.entity.clubs.TeamMembers
import com.competra.data.exception.ForbiddenException
import com.competra.data.requests.clubs.CreateTeamRequest
import com.competra.data.requests.clubs.UpdateTeamRequest
import com.competra.data.response.clubs.TeamResponse
import io.ktor.server.plugins.NotFoundException
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/** Команды видны всем публично; мутации — только FOUNDER/ADMIN клуба-владельца. */
class TeamService {

    suspend fun create(clubId: String, request: CreateTeamRequest, userId: String): TeamResponse = dbQuery {
        requireClubAdmin(clubId, userId)
        val now = System.currentTimeMillis()
        val teamId = UUID.randomUUID().toString()
        Teams.insert {
            it[id] = teamId
            it[Teams.clubId] = clubId
            it[name] = request.name
            it[sportType] = request.sportType
            it[createdAt] = now
            it[updatedAt] = now
        }
        buildResponse(teamId)
    }

    suspend fun update(teamId: String, request: UpdateTeamRequest, userId: String): TeamResponse = dbQuery {
        val team = teamRow(teamId)
        requireClubAdmin(team[Teams.clubId], userId)
        val now = System.currentTimeMillis()
        Teams.update({ Teams.id eq teamId }) {
            it[name] = request.name
            it[sportType] = request.sportType
            it[updatedAt] = now
        }
        buildResponse(teamId)
    }

    suspend fun delete(teamId: String, userId: String): Unit = dbQuery {
        val team = teamRow(teamId)
        requireClubAdmin(team[Teams.clubId], userId)
        TeamMembers.deleteWhere { TeamMembers.teamId eq teamId }
        Teams.deleteWhere { Teams.id eq teamId }
        Unit
    }

    suspend fun listByClub(clubId: String): List<TeamResponse> = dbQuery {
        Teams.selectAll()
            .where { Teams.clubId eq clubId }
            .orderBy(Teams.createdAt, SortOrder.DESC)
            .map { it.toResponse(membersCount(it[Teams.id])) }
    }

    suspend fun getById(teamId: String): TeamResponse = dbQuery {
        buildResponse(teamId)
    }

    private fun teamRow(teamId: String): ResultRow =
        Teams.selectAll().where { Teams.id eq teamId }.singleOrNull() ?: throw NotFoundException("Team not found")

    private fun requireClubAdmin(clubId: String, userId: String) {
        val membership = ClubMembers.selectAll()
            .where { (ClubMembers.clubId eq clubId) and (ClubMembers.userId eq userId) }
            .singleOrNull() ?: throw ForbiddenException("Вы не состоите в этом клубе")
        if (membership[ClubMembers.role] !in listOf("FOUNDER", "ADMIN")) {
            throw ForbiddenException("Недостаточно прав для управления командами клуба")
        }
    }

    private fun membersCount(teamId: String): Int =
        TeamMembers.selectAll().where { TeamMembers.teamId eq teamId }.count().toInt()

    private fun buildResponse(teamId: String): TeamResponse =
        teamRow(teamId).toResponse(membersCount(teamId))

    private fun ResultRow.toResponse(membersCount: Int) = TeamResponse(
        id = this[Teams.id],
        clubId = this[Teams.clubId],
        name = this[Teams.name],
        sportType = this[Teams.sportType],
        membersCount = membersCount,
        updatedAt = this[Teams.updatedAt]
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
