package com.sportenth.data.services

import com.sportenth.data.database.entity.ParticipantGroups
import com.sportenth.data.requests.orienteering.ParticipantGroupRequest
import com.sportenth.data.response.orienteering.ParticipantGroupResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class ParticipantGroupService {

    suspend fun upsertAll(requests: List<ParticipantGroupRequest>): List<ParticipantGroupResponse> = dbQuery {
        requests.map { req ->
            val groupId = req.groupId ?: UUID.randomUUID().toString()

            val existing = ParticipantGroups.selectAll()
                .where { ParticipantGroups.id eq groupId }
                .singleOrNull()

            if (existing == null) {
                ParticipantGroups.insert {
                    it[id] = groupId
                    it[competitionId] = req.competitionId
                    it[title] = req.title
                    it[gender] = req.gender
                    it[minAge] = req.minAge
                    it[maxAge] = req.maxAge
                    it[distanceId] = req.distanceId
                    it[maxParticipants] = req.maxParticipants
                }
            } else {
                ParticipantGroups.update({ ParticipantGroups.id eq groupId }) {
                    it[competitionId] = req.competitionId
                    it[title] = req.title
                    it[gender] = req.gender
                    it[minAge] = req.minAge
                    it[maxAge] = req.maxAge
                    it[distanceId] = req.distanceId
                    it[maxParticipants] = req.maxParticipants
                }
            }

            val row = ParticipantGroups.selectAll().where { ParticipantGroups.id eq groupId }.single()
            ParticipantGroupResponse(
                groupId = row[ParticipantGroups.id],
                competitionId = row[ParticipantGroups.competitionId],
                title = row[ParticipantGroups.title],
                gender = row[ParticipantGroups.gender],
                minAge = row[ParticipantGroups.minAge],
                maxAge = row[ParticipantGroups.maxAge],
                distanceId = row[ParticipantGroups.distanceId],
                maxParticipants = row[ParticipantGroups.maxParticipants]
            )
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
