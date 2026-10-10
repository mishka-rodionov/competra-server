package com.competra.data.services

import com.competra.data.database.entity.CompetitionOrganizers
import com.competra.data.exception.ConflictException
import com.competra.data.exception.ForbiddenException
import com.competra.data.requests.orienteering.OrganizerRequest
import com.competra.data.response.orienteering.OrganizerResponse
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.competra.data.database.ioTransaction
import org.jetbrains.exposed.v1.jdbc.update

class CompetitionOrganizerService {

    suspend fun upsertAll(requests: List<OrganizerRequest>, callerUserId: String): List<OrganizerResponse> = dbQuery {
        val now = System.currentTimeMillis()
        requests.map { req ->
            val existing = req.organizerId?.let { id ->
                CompetitionOrganizers.selectAll()
                    .where { CompetitionOrganizers.id eq id }
                    .singleOrNull()
            }

            val serverTs = existing?.get(CompetitionOrganizers.updatedAt) ?: 0L
            if (existing != null && req.serverUpdatedAt != null && req.serverUpdatedAt > 0L &&
                req.serverUpdatedAt < serverTs
            ) {
                throw ConflictException(existing.toResponse(), req.serverUpdatedAt, serverTs)
            }

            if (existing != null && existing[CompetitionOrganizers.competitionId] != req.competitionId) {
                throw ForbiddenException("Организатор принадлежит другому соревнованию")
            }
            requireOrganizerManageAccess(req.competitionId, callerUserId)

            val id = if (existing == null) {
                CompetitionOrganizers.insert {
                    it[competitionId] = req.competitionId
                    it[userId] = req.userId
                    it[name] = req.name
                    it[role] = req.role
                    it[contactEmail] = req.contactEmail
                    it[contactPhone] = req.contactPhone
                    it[updatedAt] = now
                } get CompetitionOrganizers.id
            } else {
                CompetitionOrganizers.update({ CompetitionOrganizers.id eq existing[CompetitionOrganizers.id] }) {
                    it[userId] = req.userId
                    it[name] = req.name
                    it[role] = req.role
                    it[contactEmail] = req.contactEmail
                    it[contactPhone] = req.contactPhone
                    it[updatedAt] = now
                }
                existing[CompetitionOrganizers.id]
            }

            CompetitionOrganizers.selectAll().where { CompetitionOrganizers.id eq id }.single().toResponse()
        }
    }

    suspend fun getByCompetition(competitionId: String): List<OrganizerResponse> = dbQuery {
        CompetitionOrganizers.selectAll()
            .where { CompetitionOrganizers.competitionId eq competitionId }
            .map { it.toResponse() }
    }

    suspend fun deleteById(id: Long, callerUserId: String): Boolean = dbQuery {
        val existing = CompetitionOrganizers.selectAll().where { CompetitionOrganizers.id eq id }.singleOrNull()
            ?: return@dbQuery false
        requireOrganizerManageAccess(existing[CompetitionOrganizers.competitionId], callerUserId)
        @Suppress("DEPRECATION")
        CompetitionOrganizers.deleteWhere { CompetitionOrganizers.id eq id } > 0
    }

    private fun ResultRow.toResponse() = OrganizerResponse(
        organizerId = this[CompetitionOrganizers.id],
        competitionId = this[CompetitionOrganizers.competitionId],
        userId = this[CompetitionOrganizers.userId],
        name = this[CompetitionOrganizers.name],
        role = this[CompetitionOrganizers.role],
        contactEmail = this[CompetitionOrganizers.contactEmail],
        contactPhone = this[CompetitionOrganizers.contactPhone],
        updatedAt = this[CompetitionOrganizers.updatedAt]
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        ioTransaction { block() }
}
