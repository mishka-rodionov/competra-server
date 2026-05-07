package com.sportenth.data.services

import com.sportenth.data.database.entity.ParticipantGroups
import com.sportenth.data.requests.orienteering.ParticipantGroupRequest
import com.sportenth.data.response.orienteering.ParticipantGroupResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class ParticipantGroupService {

    suspend fun upsertAll(requests: List<ParticipantGroupRequest>): List<ParticipantGroupResponse> = dbQuery {
        val now = System.currentTimeMillis()
        requests.map { req ->
            if (req.groupId == null) {
                val generatedId = ParticipantGroups.insert {
                    it[competitionId] = req.competitionId
                    it[title] = req.title
                    it[gender] = req.gender
                    it[minAge] = req.minAge
                    it[maxAge] = req.maxAge
                    it[distanceId] = req.distanceId
                    it[maxParticipants] = req.maxParticipants
                    it[updatedAt] = now
                } get ParticipantGroups.id

                ParticipantGroups.selectAll()
                    .where { ParticipantGroups.id eq generatedId }
                    .single()
                    .toResponse()
            } else {
                val existing = ParticipantGroups.selectAll()
                    .where { ParticipantGroups.id eq req.groupId }
                    .singleOrNull()

                if (existing == null) {
                    ParticipantGroups.insert {
                        it[id] = req.groupId
                        it[competitionId] = req.competitionId
                        it[title] = req.title
                        it[gender] = req.gender
                        it[minAge] = req.minAge
                        it[maxAge] = req.maxAge
                        it[distanceId] = req.distanceId
                        it[maxParticipants] = req.maxParticipants
                        it[updatedAt] = now
                    }
                } else {
                    ParticipantGroups.update({ ParticipantGroups.id eq req.groupId }) {
                        it[competitionId] = req.competitionId
                        it[title] = req.title
                        it[gender] = req.gender
                        it[minAge] = req.minAge
                        it[maxAge] = req.maxAge
                        it[distanceId] = req.distanceId
                        it[maxParticipants] = req.maxParticipants
                        it[updatedAt] = now
                    }
                }

                ParticipantGroups.selectAll()
                    .where { ParticipantGroups.id eq req.groupId }
                    .single()
                    .toResponse()
            }
        }
    }

    suspend fun getByCompetition(competitionId: Long): List<ParticipantGroupResponse> = dbQuery {
        ParticipantGroups.selectAll()
            .where { ParticipantGroups.competitionId eq competitionId }
            .map { it.toResponse() }
    }

    private fun ResultRow.toResponse() = ParticipantGroupResponse(
        groupId = this[ParticipantGroups.id],
        competitionId = this[ParticipantGroups.competitionId],
        title = this[ParticipantGroups.title],
        gender = this[ParticipantGroups.gender],
        minAge = this[ParticipantGroups.minAge],
        maxAge = this[ParticipantGroups.maxAge],
        distanceId = this[ParticipantGroups.distanceId],
        maxParticipants = this[ParticipantGroups.maxParticipants],
        updatedAt = this[ParticipantGroups.updatedAt]
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
