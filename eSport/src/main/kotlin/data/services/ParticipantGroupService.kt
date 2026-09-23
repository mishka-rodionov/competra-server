package com.competra.data.services

import com.competra.data.database.entity.ParticipantGroups
import com.competra.data.exception.ConflictException
import com.competra.data.exception.ForbiddenException
import com.competra.data.requests.orienteering.ParticipantGroupRequest
import com.competra.data.response.orienteering.ParticipantGroupResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class ParticipantGroupService {

    suspend fun upsertAll(requests: List<ParticipantGroupRequest>, userId: String): List<ParticipantGroupResponse> = dbQuery {
        val now = System.currentTimeMillis()
        // Группы, у которых поменялось собственное КВ: их результаты нужно пересчитать
        // (снятие за превышение обратимо — см. ResultRanking).
        val groupsWithChangedControlTime = mutableListOf<Pair<String, Long>>()
        val responses = requests.map { req ->
            if (req.groupId == null) {
                requireGroupEditAccess(req.competitionId, userId)
                val generatedId = ParticipantGroups.insert {
                    it[competitionId] = req.competitionId
                    it[title] = req.title
                    it[gender] = req.gender
                    it[minAge] = req.minAge
                    it[maxAge] = req.maxAge
                    it[distanceId] = req.distanceId
                    it[maxParticipants] = req.maxParticipants
                    it[timeLimitMinutes] = req.timeLimitMinutes
                    it[scorePenaltyPerMinute] = req.scorePenaltyPerMinute
                    it[maxLatenessMinutes] = req.maxLatenessMinutes
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

                val serverTs = existing?.get(ParticipantGroups.updatedAt) ?: 0L
                if (existing != null && req.serverUpdatedAt != null && req.serverUpdatedAt > 0L &&
                    req.serverUpdatedAt < serverTs
                ) {
                    throw ConflictException(existing.toResponse(), req.serverUpdatedAt, serverTs)
                }

                if (existing == null) {
                    requireGroupEditAccess(req.competitionId, userId)
                    ParticipantGroups.insert {
                        it[id] = req.groupId
                        it[competitionId] = req.competitionId
                        it[title] = req.title
                        it[gender] = req.gender
                        it[minAge] = req.minAge
                        it[maxAge] = req.maxAge
                        it[distanceId] = req.distanceId
                        it[maxParticipants] = req.maxParticipants
                        it[timeLimitMinutes] = req.timeLimitMinutes
                        it[scorePenaltyPerMinute] = req.scorePenaltyPerMinute
                        it[maxLatenessMinutes] = req.maxLatenessMinutes
                        it[updatedAt] = now
                    }
                } else {
                    if (existing[ParticipantGroups.competitionId] != req.competitionId) {
                        throw ForbiddenException("Группа участников принадлежит другому соревнованию")
                    }
                    requireGroupEditAccess(req.competitionId, userId)
                    if (existing[ParticipantGroups.timeLimitMinutes] != req.timeLimitMinutes) {
                        groupsWithChangedControlTime += req.competitionId to req.groupId
                    }
                    ParticipantGroups.update({ ParticipantGroups.id eq req.groupId }) {
                        it[competitionId] = req.competitionId
                        it[title] = req.title
                        it[gender] = req.gender
                        it[minAge] = req.minAge
                        it[maxAge] = req.maxAge
                        it[distanceId] = req.distanceId
                        it[maxParticipants] = req.maxParticipants
                        it[timeLimitMinutes] = req.timeLimitMinutes
                        it[scorePenaltyPerMinute] = req.scorePenaltyPerMinute
                        it[maxLatenessMinutes] = req.maxLatenessMinutes
                        it[updatedAt] = now
                    }
                }

                ParticipantGroups.selectAll()
                    .where { ParticipantGroups.id eq req.groupId }
                    .single()
                    .toResponse()
            }
        }

        groupsWithChangedControlTime.distinct().forEach { (competitionId, groupId) ->
            ResultRanking.recalculateGroup(competitionId, groupId)
        }

        responses
    }

    suspend fun getByCompetition(competitionId: String): List<ParticipantGroupResponse> = dbQuery {
        ParticipantGroups.selectAll()
            .where { ParticipantGroups.competitionId eq competitionId }
            .map { it.toResponse() }
    }

    suspend fun deleteById(id: Long, userId: String): Boolean = dbQuery {
        val existing = ParticipantGroups.selectAll().where { ParticipantGroups.id eq id }.singleOrNull()
            ?: return@dbQuery false
        requireGroupEditAccess(existing[ParticipantGroups.competitionId], userId)
        @Suppress("DEPRECATION")
        ParticipantGroups.deleteWhere { ParticipantGroups.id eq id } > 0
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
        timeLimitMinutes = this[ParticipantGroups.timeLimitMinutes],
        scorePenaltyPerMinute = this[ParticipantGroups.scorePenaltyPerMinute],
        maxLatenessMinutes = this[ParticipantGroups.maxLatenessMinutes],
        updatedAt = this[ParticipantGroups.updatedAt]
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
