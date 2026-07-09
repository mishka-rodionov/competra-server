package com.competra.data.services

import com.competra.UserService
import com.competra.data.database.entity.clubs.ClubMembers
import com.competra.data.database.entity.clubs.TeamMembers
import com.competra.data.database.entity.clubs.Teams
import com.competra.data.exception.ForbiddenException
import com.competra.data.response.clubs.TeamMemberResponse
import io.ktor.server.plugins.NotFoundException
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class TeamMemberService {

    suspend fun listMembers(teamId: String): List<TeamMemberResponse> = dbQuery {
        joinedQuery().where { TeamMembers.teamId eq teamId }.map { it.toResponse() }
    }

    /** Только FOUNDER/ADMIN клуба-владельца команды. clubMemberId должен принадлежать тому же клубу. */
    suspend fun addMember(
        teamId: String,
        clubMemberId: String,
        role: String,
        requesterUserId: String
    ): TeamMemberResponse = dbQuery {
        require(role in listOf("CAPTAIN", "MEMBER")) { "Недопустимая роль: $role" }
        val team = teamRow(teamId)
        val clubId = team[Teams.clubId]
        requireClubAdmin(clubId, requesterUserId)

        val clubMember = ClubMembers.selectAll().where { ClubMembers.id eq clubMemberId }.singleOrNull()
            ?: throw NotFoundException("Участник клуба не найден")
        if (clubMember[ClubMembers.clubId] != clubId) {
            throw ForbiddenException("Участник принадлежит другому клубу")
        }

        val existing = TeamMembers.selectAll()
            .where { (TeamMembers.teamId eq teamId) and (TeamMembers.clubMemberId eq clubMemberId) }
            .singleOrNull()
        val now = System.currentTimeMillis()
        val teamMemberId = if (existing != null) {
            TeamMembers.update({ (TeamMembers.teamId eq teamId) and (TeamMembers.clubMemberId eq clubMemberId) }) {
                it[TeamMembers.role] = role
            }
            existing[TeamMembers.id]
        } else {
            val id = UUID.randomUUID().toString()
            TeamMembers.insert {
                it[TeamMembers.id] = id
                it[TeamMembers.teamId] = teamId
                it[TeamMembers.clubMemberId] = clubMemberId
                it[TeamMembers.role] = role
                it[joinedAt] = now
            }
            id
        }
        buildResponse(teamMemberId)
    }

    /** Самостоятельный выход из команды разрешён; иначе — только FOUNDER/ADMIN клуба. */
    suspend fun removeMember(teamId: String, clubMemberId: String, requesterUserId: String): Unit = dbQuery {
        val team = teamRow(teamId)
        val requesterMembership = ClubMembers.selectAll()
            .where { (ClubMembers.clubId eq team[Teams.clubId]) and (ClubMembers.userId eq requesterUserId) }
            .singleOrNull() ?: throw ForbiddenException("Вы не состоите в этом клубе")

        val isSelf = requesterMembership[ClubMembers.id] == clubMemberId
        val isAdmin = requesterMembership[ClubMembers.role] in listOf("FOUNDER", "ADMIN")
        if (!isSelf && !isAdmin) {
            throw ForbiddenException("Недостаточно прав для удаления участника команды")
        }

        TeamMembers.deleteWhere { (TeamMembers.teamId eq teamId) and (TeamMembers.clubMemberId eq clubMemberId) }
        Unit
    }

    suspend fun changeRole(
        teamId: String,
        clubMemberId: String,
        newRole: String,
        requesterUserId: String
    ): TeamMemberResponse = dbQuery {
        require(newRole in listOf("CAPTAIN", "MEMBER")) { "Недопустимая роль: $newRole" }
        val team = teamRow(teamId)
        requireClubAdmin(team[Teams.clubId], requesterUserId)

        val member = TeamMembers.selectAll()
            .where { (TeamMembers.teamId eq teamId) and (TeamMembers.clubMemberId eq clubMemberId) }
            .singleOrNull() ?: throw NotFoundException("Участник команды не найден")

        TeamMembers.update({ (TeamMembers.teamId eq teamId) and (TeamMembers.clubMemberId eq clubMemberId) }) {
            it[role] = newRole
        }
        buildResponse(member[TeamMembers.id])
    }

    private fun teamRow(teamId: String): ResultRow =
        Teams.selectAll().where { Teams.id eq teamId }.singleOrNull() ?: throw NotFoundException("Team not found")

    private fun requireClubAdmin(clubId: String, userId: String) {
        val membership = ClubMembers.selectAll()
            .where { (ClubMembers.clubId eq clubId) and (ClubMembers.userId eq userId) }
            .singleOrNull() ?: throw ForbiddenException("Вы не состоите в этом клубе")
        if (membership[ClubMembers.role] !in listOf("FOUNDER", "ADMIN")) {
            throw ForbiddenException("Недостаточно прав для управления командой")
        }
    }

    private fun joinedQuery() = TeamMembers
        .join(ClubMembers, JoinType.INNER, TeamMembers.clubMemberId, ClubMembers.id)
        .join(UserService.Users, JoinType.INNER, ClubMembers.userId, UserService.Users.id)
        .selectAll()

    private fun buildResponse(teamMemberId: String): TeamMemberResponse =
        joinedQuery().where { TeamMembers.id eq teamMemberId }.single().toResponse()

    private fun ResultRow.toResponse() = TeamMemberResponse(
        id = this[TeamMembers.id],
        teamId = this[TeamMembers.teamId],
        clubMemberId = this[TeamMembers.clubMemberId],
        userId = this[ClubMembers.userId],
        firstName = this[UserService.Users.firstName],
        lastName = this[UserService.Users.lastName],
        role = this[TeamMembers.role],
        joinedAt = this[TeamMembers.joinedAt]
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
