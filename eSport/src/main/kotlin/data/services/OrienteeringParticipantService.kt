package com.sportenth.data.services

import com.sportenth.data.database.entity.OrienteeringParticipants
import com.sportenth.data.database.entity.ParticipantGroups
import com.sportenth.data.requests.orienteering.OrienteeringParticipantRequest
import com.sportenth.data.requests.orienteering.RegisterParticipantRequest
import com.sportenth.data.response.orienteering.OrienteeringParticipantResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

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

    /**
     * Регистрирует пользователя как участника соревнования.
     * Если пользователь уже зарегистрирован — выбрасывает IllegalStateException.
     */
    suspend fun register(req: RegisterParticipantRequest, userId: String): OrienteeringParticipantResponse = dbQuery {
        // Проверяем, не зарегистрирован ли уже пользователь на это соревнование
        val alreadyRegistered = OrienteeringParticipants.selectAll()
            .where {
                (OrienteeringParticipants.userId eq userId) and
                (OrienteeringParticipants.competitionId eq req.competitionId)
            }
            .singleOrNull()

        if (alreadyRegistered != null) {
            throw IllegalStateException("Вы уже зарегистрированы на данный старт")
        }

        // Получаем название группы
        val groupName = ParticipantGroups.selectAll()
            .where { ParticipantGroups.id eq req.groupId }
            .singleOrNull()
            ?.get(ParticipantGroups.title)
            ?: req.groupId

        val participantId = UUID.randomUUID().toString()
        OrienteeringParticipants.insert {
            it[id] = participantId
            it[OrienteeringParticipants.userId] = userId
            it[firstName] = req.firstName
            it[lastName] = req.lastName
            it[groupId] = req.groupId
            it[OrienteeringParticipants.groupName] = groupName
            it[competitionId] = req.competitionId
            it[commandName] = null
            it[startNumber] = 0
            it[startTime] = 0L
            it[chipNumber] = 0L
            it[comment] = null
            it[isChipGiven] = false
        }

        val row = OrienteeringParticipants.selectAll()
            .where { OrienteeringParticipants.id eq participantId }
            .single()
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

    /**
     * Отменяет регистрацию пользователя на соревнование.
     */
    suspend fun cancelRegistration(competitionId: Long, userId: String) = dbQuery {
        OrienteeringParticipants.deleteWhere {
            (OrienteeringParticipants.userId eq userId) and
            (OrienteeringParticipants.competitionId eq competitionId)
        }
    }

    /**
     * Проверяет, зарегистрирован ли пользователь на данное соревнование.
     */
    suspend fun isRegistered(competitionId: Long, userId: String): Boolean = dbQuery {
        OrienteeringParticipants.selectAll()
            .where {
                (OrienteeringParticipants.userId eq userId) and
                (OrienteeringParticipants.competitionId eq competitionId)
            }
            .singleOrNull() != null
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
