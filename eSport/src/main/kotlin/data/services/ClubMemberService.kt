package com.competra.data.services

import com.competra.UserService
import com.competra.data.database.entity.clubs.ClubMembers
import com.competra.data.exception.ForbiddenException
import com.competra.data.response.clubs.ClubMemberResponse
import io.ktor.server.plugins.NotFoundException
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class ClubMemberService {

    suspend fun listMembers(clubId: String): List<ClubMemberResponse> = dbQuery {
        ClubMembers
            .join(
                otherTable = UserService.Users,
                joinType = JoinType.INNER,
                onColumn = ClubMembers.userId,
                otherColumn = UserService.Users.id
            )
            .selectAll()
            .where { ClubMembers.clubId eq clubId }
            .map { it.toResponse() }
    }

    /**
     * requesterUserId == targetUserId — самостоятельный выход. Иначе — удаление участника,
     * доступно только FOUNDER/ADMIN. В обоих случаях единственного FOUNDER удалить/вывести
     * нельзя — единственный путь для него: [ClubService.delete].
     */
    suspend fun removeMember(clubId: String, requesterUserId: String, targetUserId: String): Unit = dbQuery {
        val target = membership(clubId, targetUserId)
            ?: throw NotFoundException("Участник не найден в клубе")

        if (target[ClubMembers.role] == "FOUNDER") {
            throw ForbiddenException(
                "Единственный Founder не может покинуть клуб или быть удалён — удалите клуб целиком"
            )
        }

        if (requesterUserId != targetUserId) {
            val requester = membership(clubId, requesterUserId)
                ?: throw ForbiddenException("Вы не состоите в этом клубе")
            if (requester[ClubMembers.role] !in listOf("FOUNDER", "ADMIN")) {
                throw ForbiddenException("Недостаточно прав для удаления участника")
            }
        }

        ClubMembers.deleteWhere { (ClubMembers.clubId eq clubId) and (ClubMembers.userId eq targetUserId) }
        Unit
    }

    /**
     * newRole == "FOUNDER" — передача роли основателя: requesterUserId (текущий FOUNDER)
     * передаёт роль targetUserId, сам становится MEMBER. Иначе (ADMIN/MEMBER) — назначение
     * или снятие ADMIN, доступно только текущему FOUNDER.
     */
    suspend fun changeRole(
        clubId: String,
        requesterUserId: String,
        targetUserId: String,
        newRole: String
    ): ClubMemberResponse = dbQuery {
        require(newRole in listOf("FOUNDER", "ADMIN", "MEMBER")) { "Недопустимая роль: $newRole" }

        val requester = membership(clubId, requesterUserId)
            ?: throw ForbiddenException("Вы не состоите в этом клубе")
        if (requester[ClubMembers.role] != "FOUNDER") {
            throw ForbiddenException("Менять роли участников может только Founder клуба")
        }

        val target = membership(clubId, targetUserId)
            ?: throw NotFoundException("Участник не найден в клубе")

        if (newRole == "FOUNDER") {
            ClubMembers.update({ (ClubMembers.clubId eq clubId) and (ClubMembers.userId eq targetUserId) }) {
                it[role] = "FOUNDER"
            }
            ClubMembers.update({ (ClubMembers.clubId eq clubId) and (ClubMembers.userId eq requesterUserId) }) {
                it[role] = "MEMBER"
            }
        } else {
            if (target[ClubMembers.role] == "FOUNDER") {
                throw ForbiddenException("Нельзя понизить единственного Founder — передайте роль явно")
            }
            ClubMembers.update({ (ClubMembers.clubId eq clubId) and (ClubMembers.userId eq targetUserId) }) {
                it[role] = newRole
            }
        }

        buildResponse(clubId, targetUserId)
    }

    private fun membership(clubId: String, userId: String): ResultRow? =
        ClubMembers.selectAll()
            .where { (ClubMembers.clubId eq clubId) and (ClubMembers.userId eq userId) }
            .singleOrNull()

    private fun buildResponse(clubId: String, userId: String): ClubMemberResponse {
        val row = ClubMembers
            .join(
                otherTable = UserService.Users,
                joinType = JoinType.INNER,
                onColumn = ClubMembers.userId,
                otherColumn = UserService.Users.id
            )
            .selectAll()
            .where { (ClubMembers.clubId eq clubId) and (ClubMembers.userId eq userId) }
            .single()
        return row.toResponse()
    }

    private fun ResultRow.toResponse() = ClubMemberResponse(
        id = this[ClubMembers.id],
        clubId = this[ClubMembers.clubId],
        userId = this[ClubMembers.userId],
        firstName = this[UserService.Users.firstName],
        lastName = this[UserService.Users.lastName],
        role = this[ClubMembers.role],
        joinedAt = this[ClubMembers.joinedAt]
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
