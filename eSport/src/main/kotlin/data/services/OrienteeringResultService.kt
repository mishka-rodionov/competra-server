package com.competra.data.services

import com.competra.data.database.entity.OrienteeringResults
import com.competra.data.database.entity.SplitTimes
import com.competra.data.exception.ConflictException
import com.competra.data.exception.ForbiddenException
import com.competra.data.requests.orienteering.OrienteeringResultRequest
import com.competra.data.response.orienteering.OrienteeringResultResponse
import com.competra.data.response.orienteering.SplitTimeResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class OrienteeringResultService {

    suspend fun upsert(req: OrienteeringResultRequest, callerUserId: String): OrienteeringResultResponse = dbQuery {
        upsertSingle(req, callerUserId)
        ResultRanking.recalculateGroup(req.competitionId, req.groupId)
        loadResponse(req.id)
    }

    /**
     * Batch-upsert с одним пересчётом мест на каждую уникальную пару (competitionId, groupId).
     */
    suspend fun upsertAll(requests: List<OrienteeringResultRequest>, callerUserId: String): List<OrienteeringResultResponse> = dbQuery {
        if (requests.isEmpty()) return@dbQuery emptyList()
        requests.forEach { upsertSingle(it, callerUserId) }
        requests
            .map { it.competitionId to it.groupId }
            .distinct()
            .forEach { (competitionId, groupId) -> ResultRanking.recalculateGroup(competitionId, groupId) }
        requests.map { loadResponse(it.id) }
    }

    suspend fun deleteById(id: String, callerUserId: String): Boolean = dbQuery {
        val existing = OrienteeringResults.selectAll().where { OrienteeringResults.id eq id }.singleOrNull()
            ?: return@dbQuery false
        requireResultEditAccess(existing[OrienteeringResults.competitionId], callerUserId)
        @Suppress("DEPRECATION")
        SplitTimes.deleteWhere { resultId eq id }
        @Suppress("DEPRECATION")
        OrienteeringResults.deleteWhere { OrienteeringResults.id eq id } > 0
    }

    private fun upsertSingle(req: OrienteeringResultRequest, callerUserId: String) {
        val now = System.currentTimeMillis()
        val existing = OrienteeringResults.selectAll()
            .where { OrienteeringResults.id eq req.id }
            .singleOrNull()

        val serverTs = existing?.get(OrienteeringResults.updatedAt) ?: 0L
        if (existing != null && req.serverUpdatedAt != null && req.serverUpdatedAt > 0L &&
            req.serverUpdatedAt < serverTs
        ) {
            val splits = SplitTimes.selectAll()
                .where { SplitTimes.resultId eq req.id }
                .orderBy(SplitTimes.timestamp)
                .map { com.competra.data.response.orienteering.SplitTimeResponse(
                    it[SplitTimes.controlPoint],
                    it[SplitTimes.timestamp]
                ) }
            throw ConflictException(existing.toResponse(splits), req.serverUpdatedAt, serverTs)
        }

        if (existing != null && existing[OrienteeringResults.competitionId] != req.competitionId) {
            throw ForbiddenException("Результат принадлежит другому соревнованию")
        }
        requireResultEditAccess(req.competitionId, callerUserId)

        if (existing == null) {
            OrienteeringResults.insert {
                it[id] = req.id
                it[competitionId] = req.competitionId
                it[groupId] = req.groupId
                it[participantId] = req.participantId
                it[startTime] = req.startTime
                it[finishTime] = req.finishTime
                it[totalTime] = req.totalTime
                it[rank] = req.rank
                it[status] = req.status
                it[penaltyTime] = req.penaltyTime
                it[totalScore] = req.totalScore
                it[scorePenalty] = req.scorePenalty
                it[isEditable] = req.isEditable
                it[isEdited] = req.isEdited
                it[updatedAt] = now
            }
        } else {
            OrienteeringResults.update({ OrienteeringResults.id eq req.id }) {
                it[competitionId] = req.competitionId
                it[groupId] = req.groupId
                it[participantId] = req.participantId
                it[startTime] = req.startTime
                it[finishTime] = req.finishTime
                it[totalTime] = req.totalTime
                it[rank] = req.rank
                it[status] = req.status
                it[penaltyTime] = req.penaltyTime
                it[totalScore] = req.totalScore
                it[scorePenalty] = req.scorePenalty
                it[isEditable] = req.isEditable
                it[isEdited] = req.isEdited
                it[updatedAt] = now
            }
        }

        @Suppress("DEPRECATION")
        SplitTimes.deleteWhere { resultId eq req.id }
        req.splits?.forEach { split ->
            SplitTimes.insert {
                it[resultId] = req.id
                it[controlPoint] = split.controlPoint
                it[timestamp] = split.timestamp
                it[updatedAt] = now
            }
        }
    }

    private fun loadResponse(resultId: String): OrienteeringResultResponse {
        val row = OrienteeringResults.selectAll().where { OrienteeringResults.id eq resultId }.single()
        val splits = SplitTimes.selectAll()
            .where { SplitTimes.resultId eq resultId }
            .orderBy(SplitTimes.timestamp)
            .map { SplitTimeResponse(it[SplitTimes.controlPoint], it[SplitTimes.timestamp]) }
        return row.toResponse(splits)
    }

    suspend fun getByCompetition(competitionId: String): List<OrienteeringResultResponse> = dbQuery {
        OrienteeringResults.selectAll()
            .where { OrienteeringResults.competitionId eq competitionId }
            .map { row ->
                val splits = SplitTimes.selectAll()
                    .where { SplitTimes.resultId eq row[OrienteeringResults.id] }
                    .orderBy(SplitTimes.timestamp)
                    .map { SplitTimeResponse(it[SplitTimes.controlPoint], it[SplitTimes.timestamp]) }
                row.toResponse(splits)
            }
    }

    private fun ResultRow.toResponse(splits: List<SplitTimeResponse>) = OrienteeringResultResponse(
        id = this[OrienteeringResults.id],
        competitionId = this[OrienteeringResults.competitionId],
        groupId = this[OrienteeringResults.groupId],
        participantId = this[OrienteeringResults.participantId],
        startTime = this[OrienteeringResults.startTime],
        finishTime = this[OrienteeringResults.finishTime],
        totalTime = this[OrienteeringResults.totalTime],
        rank = this[OrienteeringResults.rank],
        status = this[OrienteeringResults.status],
        penaltyTime = this[OrienteeringResults.penaltyTime],
        totalScore = this[OrienteeringResults.totalScore],
        scorePenalty = this[OrienteeringResults.scorePenalty],
        splits = splits,
        isEditable = this[OrienteeringResults.isEditable],
        isEdited = this[OrienteeringResults.isEdited],
        updatedAt = this[OrienteeringResults.updatedAt]
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
