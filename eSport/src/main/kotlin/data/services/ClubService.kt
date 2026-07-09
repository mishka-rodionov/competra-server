package com.competra.data.services

import com.competra.data.database.entity.clubs.ClubMembers
import com.competra.data.database.entity.clubs.Clubs
import com.competra.data.exception.ForbiddenException
import com.competra.data.requests.clubs.CreateClubRequest
import com.competra.data.requests.clubs.UpdateClubRequest
import com.competra.data.response.base.PagedResponse
import com.competra.data.response.clubs.ClubResponse
import io.ktor.server.plugins.NotFoundException
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/** Клуб всегда публичен: список/детали доступны без авторизации, мутации — только FOUNDER/ADMIN. */
class ClubService {

    suspend fun create(request: CreateClubRequest, userId: String): ClubResponse = dbQuery {
        val now = System.currentTimeMillis()
        val clubId = UUID.randomUUID().toString()

        Clubs.insert {
            it[id] = clubId
            it[name] = request.name
            it[description] = request.description
            it[allowJoinRequests] = request.allowJoinRequests
            it[foundedAt] = now
            it[updatedAt] = now
        }

        // Создатель клуба автоматически становится FOUNDER.
        ClubMembers.insert {
            it[id] = UUID.randomUUID().toString()
            it[ClubMembers.clubId] = clubId
            it[ClubMembers.userId] = userId
            it[role] = "FOUNDER"
            it[joinedAt] = now
        }

        buildResponse(clubId)
    }

    suspend fun update(clubId: String, request: UpdateClubRequest, userId: String): ClubResponse = dbQuery {
        requireRole(clubId, userId, "FOUNDER", "ADMIN")
        val now = System.currentTimeMillis()
        val updated = Clubs.update({ Clubs.id eq clubId }) {
            it[name] = request.name
            it[description] = request.description
            it[allowJoinRequests] = request.allowJoinRequests
            it[updatedAt] = now
        }
        if (updated == 0) throw NotFoundException("Club not found")
        buildResponse(clubId)
    }

    /** Только FOUNDER. Соревнования с organizingClubId на этот клуб не удаляются (ON DELETE SET NULL). */
    suspend fun delete(clubId: String, userId: String): Unit = dbQuery {
        requireRole(clubId, userId, "FOUNDER")
        ClubMembers.deleteWhere { ClubMembers.clubId eq clubId }
        Clubs.deleteWhere { Clubs.id eq clubId }
        Unit
    }

    suspend fun getById(clubId: String): ClubResponse = dbQuery {
        buildResponse(clubId)
    }

    /** Клубы, где userId состоит в роли FOUNDER или ADMIN — используется для выбора клуба-организатора соревнования. */
    suspend fun listMine(userId: String): List<ClubResponse> = dbQuery {
        val myClubIds = ClubMembers.selectAll()
            .where { (ClubMembers.userId eq userId) and (ClubMembers.role inList listOf("FOUNDER", "ADMIN")) }
            .map { it[ClubMembers.clubId] }
        if (myClubIds.isEmpty()) return@dbQuery emptyList()
        Clubs.selectAll()
            .where { Clubs.id inList myClubIds }
            .orderBy(Clubs.foundedAt, SortOrder.DESC)
            .map { it.toResponse(membersCount(it[Clubs.id])) }
    }

    suspend fun search(query: String?, page: Int, limit: Int): PagedResponse<ClubResponse> = dbQuery {
        val baseQuery = Clubs.selectAll()
        if (!query.isNullOrBlank()) {
            baseQuery.andWhere { Clubs.name like "%$query%" }
        }
        baseQuery.orderBy(Clubs.foundedAt, SortOrder.DESC)
        baseQuery.limit(limit + 1).offset(page.toLong() * limit)

        val rows = baseQuery.toList()
        val hasMore = rows.size > limit
        val items = rows.take(limit).map { it.toResponse(membersCount(it[Clubs.id])) }
        PagedResponse(items, hasMore)
    }

    /** Бросает [ForbiddenException], если userId не состоит в клубе с одной из [roles]. */
    private fun requireRole(clubId: String, userId: String, vararg roles: String): ResultRow {
        val membership = ClubMembers.selectAll()
            .where { (ClubMembers.clubId eq clubId) and (ClubMembers.userId eq userId) }
            .singleOrNull() ?: throw ForbiddenException("Вы не состоите в этом клубе")
        if (membership[ClubMembers.role] !in roles) {
            throw ForbiddenException("Недостаточно прав: требуется одна из ролей ${roles.joinToString()}")
        }
        return membership
    }

    private fun membersCount(clubId: String): Int =
        ClubMembers.selectAll().where { ClubMembers.clubId eq clubId }.count().toInt()

    private fun buildResponse(clubId: String): ClubResponse {
        val row = Clubs.selectAll().where { Clubs.id eq clubId }.singleOrNull()
            ?: throw NotFoundException("Club not found")
        return row.toResponse(membersCount(clubId))
    }

    private fun ResultRow.toResponse(membersCount: Int) = ClubResponse(
        id = this[Clubs.id],
        name = this[Clubs.name],
        description = this[Clubs.description],
        allowJoinRequests = this[Clubs.allowJoinRequests],
        foundedAt = this[Clubs.foundedAt],
        membersCount = membersCount,
        updatedAt = this[Clubs.updatedAt]
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
