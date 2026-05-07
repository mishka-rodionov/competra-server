package com.sportenth.data.services

import com.sportenth.data.database.entity.Competitions
import com.sportenth.data.database.entity.OrienteeringCompetitions
import com.sportenth.data.database.entity.OrienteeringParticipants
import com.sportenth.data.database.entity.ParticipantGroups
import com.sportenth.data.requests.orienteering.OrienteeringParticipantRequest
import com.sportenth.data.requests.orienteering.RegisterParticipantRequest
import com.sportenth.data.response.orienteering.OrienteeringParticipantResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class OrienteeringParticipantService {

    private fun computeEffectiveStatus(
        storedStatus: String,
        registrationStart: Long?,
        registrationEnd: Long?,
        startTime: Long?
    ): String {
        if (storedStatus == "IN_PROGRESS" || storedStatus == "FINISHED") return storedStatus
        val now = System.currentTimeMillis()
        return when {
            startTime != null && now >= startTime -> "IN_PROGRESS"
            registrationEnd != null && now >= registrationEnd -> "REGISTRATION_CLOSED"
            registrationStart == null || now >= registrationStart -> "REGISTRATION_OPEN"
            else -> "CREATED"
        }
    }

    suspend fun upsertAll(requests: List<OrienteeringParticipantRequest>): List<OrienteeringParticipantResponse> = dbQuery {
        val now = System.currentTimeMillis()
        requests.map { req ->
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
                    it[updatedAt] = now
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
                    it[updatedAt] = now
                }
            }

            OrienteeringParticipants.selectAll()
                .where { OrienteeringParticipants.id eq req.id }
                .single()
                .toResponse()
        }
    }

    suspend fun upsert(req: OrienteeringParticipantRequest): OrienteeringParticipantResponse = dbQuery {
        val now = System.currentTimeMillis()
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
                it[updatedAt] = now
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
                it[updatedAt] = now
            }
        }

        OrienteeringParticipants.selectAll().where { OrienteeringParticipants.id eq req.id }.single().toResponse()
    }

    /**
     * Регистрирует пользователя как участника соревнования.
     * Если пользователь уже зарегистрирован — выбрасывает IllegalStateException.
     */
    suspend fun register(req: RegisterParticipantRequest, userId: String): OrienteeringParticipantResponse = dbQuery {
        // Проверяем статус соревнования — регистрация доступна только при REGISTRATION_OPEN
        val comp = Competitions.selectAll()
            .where { Competitions.id eq req.competitionId }
            .singleOrNull() ?: throw IllegalStateException("Соревнование не найдено")

        val orient = OrienteeringCompetitions.selectAll()
            .where { OrienteeringCompetitions.competitionId eq req.competitionId }
            .singleOrNull()

        val effectiveStatus = computeEffectiveStatus(
            storedStatus = comp[Competitions.status],
            registrationStart = comp[Competitions.registrationStart],
            registrationEnd = comp[Competitions.registrationEnd],
            startTime = orient?.get(OrienteeringCompetitions.startTime)
        )
        if (effectiveStatus != "REGISTRATION_OPEN") {
            throw IllegalStateException("Регистрация на данное соревнование недоступна")
        }

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

        val groupName = ParticipantGroups.selectAll()
            .where { ParticipantGroups.id eq req.groupId }
            .singleOrNull()
            ?.get(ParticipantGroups.title)
            ?: ""

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
            it[updatedAt] = System.currentTimeMillis()
        }

        OrienteeringParticipants.selectAll()
            .where { OrienteeringParticipants.id eq participantId }
            .single()
            .toResponse()
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

    suspend fun getByCompetition(competitionId: Long): List<OrienteeringParticipantResponse> = dbQuery {
        OrienteeringParticipants.selectAll()
            .where { OrienteeringParticipants.competitionId eq competitionId }
            .map { row -> row.toResponse() }
    }

    suspend fun getByGroup(groupId: Long): List<OrienteeringParticipantResponse> = dbQuery {
        OrienteeringParticipants.selectAll()
            .where { OrienteeringParticipants.groupId eq groupId }
            .map { it.toResponse() }
    }

    suspend fun deleteById(id: String): Boolean = dbQuery {
        @Suppress("DEPRECATION")
        OrienteeringParticipants.deleteWhere { OrienteeringParticipants.id eq id } > 0
    }

    private fun ResultRow.toResponse() = OrienteeringParticipantResponse(
        id = this[OrienteeringParticipants.id],
        userId = this[OrienteeringParticipants.userId],
        firstName = this[OrienteeringParticipants.firstName],
        lastName = this[OrienteeringParticipants.lastName],
        groupId = this[OrienteeringParticipants.groupId],
        groupName = this[OrienteeringParticipants.groupName],
        competitionId = this[OrienteeringParticipants.competitionId],
        commandName = this[OrienteeringParticipants.commandName],
        startNumber = this[OrienteeringParticipants.startNumber],
        startTime = this[OrienteeringParticipants.startTime],
        chipNumber = this[OrienteeringParticipants.chipNumber],
        comment = this[OrienteeringParticipants.comment],
        isChipGiven = this[OrienteeringParticipants.isChipGiven],
        updatedAt = this[OrienteeringParticipants.updatedAt]
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
