package com.competra.data.services

import com.competra.UserService
import com.competra.data.database.entity.clubs.ClubJoinRequests
import com.competra.data.database.entity.clubs.ClubMembers
import com.competra.data.database.entity.clubs.Clubs
import com.competra.data.exception.ForbiddenException
import com.competra.data.response.clubs.ClubJoinRequestResponse
import io.ktor.server.plugins.NotFoundException
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class ClubJoinRequestService {

    suspend fun create(clubId: String, userId: String): ClubJoinRequestResponse = dbQuery {
        val club = Clubs.selectAll().where { Clubs.id eq clubId }.singleOrNull()
            ?: throw NotFoundException("Club not found")
        if (!club[Clubs.allowJoinRequests]) {
            throw ForbiddenException("Клуб не принимает заявки на вступление")
        }
        val alreadyMember = ClubMembers.selectAll()
            .where { (ClubMembers.clubId eq clubId) and (ClubMembers.userId eq userId) }
            .singleOrNull() != null
        if (alreadyMember) throw ForbiddenException("Вы уже состоите в этом клубе")

        val existingPending = ClubJoinRequests.selectAll()
            .where {
                (ClubJoinRequests.clubId eq clubId) and
                (ClubJoinRequests.userId eq userId) and
                (ClubJoinRequests.status eq "PENDING")
            }
            .singleOrNull()
        if (existingPending != null) return@dbQuery buildResponse(existingPending[ClubJoinRequests.id])

        val now = System.currentTimeMillis()
        val requestId = UUID.randomUUID().toString()
        ClubJoinRequests.insert {
            it[id] = requestId
            it[ClubJoinRequests.clubId] = clubId
            it[ClubJoinRequests.userId] = userId
            it[status] = "PENDING"
            it[createdAt] = now
            it[updatedAt] = now
        }
        buildResponse(requestId)
    }

    suspend fun listForClub(clubId: String, requesterUserId: String): List<ClubJoinRequestResponse> = dbQuery {
        requireClubAdmin(clubId, requesterUserId)
        joinedQuery()
            .where { ClubJoinRequests.clubId eq clubId }
            .orderBy(ClubJoinRequests.createdAt, SortOrder.DESC)
            .map { it.toResponse() }
    }

    suspend fun listMine(userId: String): List<ClubJoinRequestResponse> = dbQuery {
        joinedQuery()
            .where { ClubJoinRequests.userId eq userId }
            .orderBy(ClubJoinRequests.createdAt, SortOrder.DESC)
            .map { it.toResponse() }
    }

    /** approve == true — заявка одобряется и заявитель становится MEMBER; иначе отклоняется. */
    suspend fun review(
        clubId: String,
        requestId: String,
        requesterUserId: String,
        approve: Boolean
    ): ClubJoinRequestResponse = dbQuery {
        requireClubAdmin(clubId, requesterUserId)

        val request = ClubJoinRequests.selectAll()
            .where { (ClubJoinRequests.id eq requestId) and (ClubJoinRequests.clubId eq clubId) }
            .singleOrNull() ?: throw NotFoundException("Заявка не найдена")
        if (request[ClubJoinRequests.status] != "PENDING") {
            throw ForbiddenException("Заявка уже обработана")
        }

        val now = System.currentTimeMillis()
        ClubJoinRequests.update({ ClubJoinRequests.id eq requestId }) {
            it[status] = if (approve) "APPROVED" else "REJECTED"
            it[updatedAt] = now
        }

        if (approve) {
            val targetUserId = request[ClubJoinRequests.userId]
            val alreadyMember = ClubMembers.selectAll()
                .where { (ClubMembers.clubId eq clubId) and (ClubMembers.userId eq targetUserId) }
                .singleOrNull() != null
            if (!alreadyMember) {
                ClubMembers.insert {
                    it[id] = UUID.randomUUID().toString()
                    it[ClubMembers.clubId] = clubId
                    it[ClubMembers.userId] = targetUserId
                    it[role] = "MEMBER"
                    it[joinedAt] = now
                }
            }
        }

        buildResponse(requestId)
    }

    private fun requireClubAdmin(clubId: String, userId: String) {
        val membership = ClubMembers.selectAll()
            .where { (ClubMembers.clubId eq clubId) and (ClubMembers.userId eq userId) }
            .singleOrNull() ?: throw ForbiddenException("Вы не состоите в этом клубе")
        if (membership[ClubMembers.role] !in listOf("FOUNDER", "ADMIN")) {
            throw ForbiddenException("Недостаточно прав для управления заявками клуба")
        }
    }

    private fun joinedQuery() = ClubJoinRequests
        .join(
            otherTable = UserService.Users,
            joinType = JoinType.INNER,
            onColumn = ClubJoinRequests.userId,
            otherColumn = UserService.Users.id
        )
        .selectAll()

    private fun buildResponse(requestId: String): ClubJoinRequestResponse =
        joinedQuery().where { ClubJoinRequests.id eq requestId }.single().toResponse()

    private fun ResultRow.toResponse() = ClubJoinRequestResponse(
        id = this[ClubJoinRequests.id],
        clubId = this[ClubJoinRequests.clubId],
        userId = this[ClubJoinRequests.userId],
        firstName = this[UserService.Users.firstName],
        lastName = this[UserService.Users.lastName],
        status = this[ClubJoinRequests.status],
        createdAt = this[ClubJoinRequests.createdAt]
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
