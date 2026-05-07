package com.sportenth.data.services

import com.sportenth.data.database.entity.OrienteeringResults
import com.sportenth.data.database.entity.SplitTimes
import com.sportenth.data.exception.ConflictException
import com.sportenth.data.requests.orienteering.OrienteeringResultRequest
import com.sportenth.data.response.orienteering.OrienteeringResultResponse
import com.sportenth.data.response.orienteering.SplitTimeResponse
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

    suspend fun upsert(req: OrienteeringResultRequest): OrienteeringResultResponse = dbQuery {
        upsertSingle(req)
        recalculateRanksForGroup(req.competitionId, req.groupId)
        loadResponse(req.id)
    }

    /**
     * Batch-upsert с одним пересчётом мест на каждую уникальную пару (competitionId, groupId).
     */
    suspend fun upsertAll(requests: List<OrienteeringResultRequest>): List<OrienteeringResultResponse> = dbQuery {
        if (requests.isEmpty()) return@dbQuery emptyList()
        requests.forEach { upsertSingle(it) }
        requests
            .map { it.competitionId to it.groupId }
            .distinct()
            .forEach { (competitionId, groupId) -> recalculateRanksForGroup(competitionId, groupId) }
        requests.map { loadResponse(it.id) }
    }

    suspend fun deleteById(id: String): Boolean = dbQuery {
        @Suppress("DEPRECATION")
        SplitTimes.deleteWhere { resultId eq id }
        @Suppress("DEPRECATION")
        OrienteeringResults.deleteWhere { OrienteeringResults.id eq id } > 0
    }

    private fun upsertSingle(req: OrienteeringResultRequest) {
        val now = System.currentTimeMillis()
        val existing = OrienteeringResults.selectAll()
            .where { OrienteeringResults.id eq req.id }
            .singleOrNull()

        if (existing != null && req.serverUpdatedAt != null &&
            req.serverUpdatedAt < existing[OrienteeringResults.updatedAt]
        ) {
            val splits = SplitTimes.selectAll()
                .where { SplitTimes.resultId eq req.id }
                .map { com.sportenth.data.response.orienteering.SplitTimeResponse(
                    it[SplitTimes.controlPoint],
                    it[SplitTimes.timestamp]
                ) }
            throw ConflictException(existing.toResponse(splits))
        }

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
            .map { SplitTimeResponse(it[SplitTimes.controlPoint], it[SplitTimes.timestamp]) }
        return row.toResponse(splits)
    }

    /**
     * Пересчитывает места для всех FINISHED-результатов группы.
     */
    private fun recalculateRanksForGroup(competitionId: Long, groupId: Long) {
        val finishedRows = OrienteeringResults.selectAll()
            .where {
                (OrienteeringResults.competitionId eq competitionId) and
                (OrienteeringResults.groupId eq groupId) and
                (OrienteeringResults.status eq "FINISHED")
            }
            .toList()
            .sortedBy { (it[OrienteeringResults.totalTime] ?: Long.MAX_VALUE) + it[OrienteeringResults.penaltyTime] }

        var rank = 1
        var prevTime: Long? = null
        var skipCount = 0

        finishedRows.forEachIndexed { index, row ->
            val effectiveTime = (row[OrienteeringResults.totalTime] ?: Long.MAX_VALUE) + row[OrienteeringResults.penaltyTime]

            if (prevTime != null && effectiveTime == prevTime) {
                skipCount++
            } else {
                rank = index + 1 - skipCount
                prevTime = effectiveTime
            }

            OrienteeringResults.update({ OrienteeringResults.id eq row[OrienteeringResults.id] }) {
                it[OrienteeringResults.rank] = rank
            }
        }
    }

    suspend fun getByCompetition(competitionId: Long): List<OrienteeringResultResponse> = dbQuery {
        OrienteeringResults.selectAll()
            .where { OrienteeringResults.competitionId eq competitionId }
            .map { row ->
                val splits = SplitTimes.selectAll()
                    .where { SplitTimes.resultId eq row[OrienteeringResults.id] }
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
        splits = splits,
        isEditable = this[OrienteeringResults.isEditable],
        isEdited = this[OrienteeringResults.isEdited],
        updatedAt = this[OrienteeringResults.updatedAt]
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
