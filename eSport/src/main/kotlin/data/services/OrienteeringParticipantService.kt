package com.sportenth.data.services

import com.sportenth.data.database.entity.OrienteeringParticipants
import com.sportenth.data.requests.orienteering.OrienteeringParticipantRequest
import com.sportenth.data.response.orienteering.OrienteeringParticipantResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class OrienteeringParticipantService {

    suspend fun upsert(req: OrienteeringParticipantRequest): OrienteeringParticipantResponse = dbQuery {
        val existing = OrienteeringParticipants.selectAll()
            .where { OrienteeringParticipants.id eq req.id }
            .singleOrNull()

        if (existing == null) {
            OrienteeringParticipants.insert {
                it[id] = req.id
                it[userId] = req.userId
                it[firstName] = req.firstName
                it[lastName] = req.lastName
                it[groupId] = req.groupId
                it[groupName] = req.groupName
                it[competitionId] = req.competitionId
                it[commandName] = req.commandName
                it[startNumber] = req.startNumber
                it[startTime] = req.startTime
                it[chipNumber] = req.chipNumber
                it[comment] = req.comment
                it[isChipGiven] = req.isChipGiven
            }
        } else {
            OrienteeringParticipants.update({ OrienteeringParticipants.id eq req.id }) {
                it[userId] = req.userId
                it[firstName] = req.firstName
                it[lastName] = req.lastName
                it[groupId] = req.groupId
                it[groupName] = req.groupName
                it[competitionId] = req.competitionId
                it[commandName] = req.commandName
                it[startNumber] = req.startNumber
                it[startTime] = req.startTime
                it[chipNumber] = req.chipNumber
                it[comment] = req.comment
                it[isChipGiven] = req.isChipGiven
            }
        }

        val row = OrienteeringParticipants.selectAll().where { OrienteeringParticipants.id eq req.id }.single()
        OrienteeringParticipantResponse(
            id = row[OrienteeringParticipants.id],
            userId = row[OrienteeringParticipants.userId],
            firstName = row[OrienteeringParticipants.firstName],
            lastName = row[OrienteeringParticipants.lastName],
            groupId = row[OrienteeringParticipants.groupId],
            groupName = row[OrienteeringParticipants.groupName],
            competitionId = row[OrienteeringParticipants.competitionId],
            commandName = row[OrienteeringParticipants.commandName],
            startNumber = row[OrienteeringParticipants.startNumber],
            startTime = row[OrienteeringParticipants.startTime],
            chipNumber = row[OrienteeringParticipants.chipNumber],
            comment = row[OrienteeringParticipants.comment],
            isChipGiven = row[OrienteeringParticipants.isChipGiven]
        )
    }

    suspend fun getByGroup(groupId: String): List<OrienteeringParticipantResponse> = dbQuery {
        OrienteeringParticipants.selectAll()
            .where { OrienteeringParticipants.groupId eq groupId }
            .map { row ->
                OrienteeringParticipantResponse(
                    id = row[OrienteeringParticipants.id],
                    userId = row[OrienteeringParticipants.userId],
                    firstName = row[OrienteeringParticipants.firstName],
                    lastName = row[OrienteeringParticipants.lastName],
                    groupId = row[OrienteeringParticipants.groupId],
                    groupName = row[OrienteeringParticipants.groupName],
                    competitionId = row[OrienteeringParticipants.competitionId],
                    commandName = row[OrienteeringParticipants.commandName],
                    startNumber = row[OrienteeringParticipants.startNumber],
                    startTime = row[OrienteeringParticipants.startTime],
                    chipNumber = row[OrienteeringParticipants.chipNumber],
                    comment = row[OrienteeringParticipants.comment],
                    isChipGiven = row[OrienteeringParticipants.isChipGiven]
                )
            }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
