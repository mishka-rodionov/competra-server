package com.sportenth.data.services

import com.sportenth.data.database.entity.OrienteeringResults
import com.sportenth.data.database.entity.SplitTimes
import com.sportenth.data.requests.orienteering.OrienteeringResultRequest
import com.sportenth.data.response.orienteering.OrienteeringResultResponse
import com.sportenth.data.response.orienteering.SplitTimeResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class OrienteeringResultService {

    suspend fun upsert(req: OrienteeringResultRequest): OrienteeringResultResponse = dbQuery {
        val existing = OrienteeringResults.selectAll()
            .where { OrienteeringResults.id eq req.id }
            .singleOrNull()

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
            }
        }

        // Replace splits: delete old, insert new
        SplitTimes.deleteWhere { resultId eq req.id }
        req.splits?.forEach { split ->
            SplitTimes.insert {
                it[resultId] = req.id
                it[controlPoint] = split.controlPoint
                it[timestamp] = split.timestamp
            }
        }

        // Пересчитываем места для всей группы внутри той же транзакции
        recalculateRanksForGroup(req.competitionId, req.groupId)

        val row = OrienteeringResults.selectAll().where { OrienteeringResults.id eq req.id }.single()
        val splits = SplitTimes.selectAll()
            .where { SplitTimes.resultId eq req.id }
            .map { SplitTimeResponse(it[SplitTimes.controlPoint], it[SplitTimes.timestamp]) }

        OrienteeringResultResponse(
            id = row[OrienteeringResults.id],
            competitionId = row[OrienteeringResults.competitionId],
            groupId = row[OrienteeringResults.groupId],
            participantId = row[OrienteeringResults.participantId],
            startTime = row[OrienteeringResults.startTime],
            finishTime = row[OrienteeringResults.finishTime],
            totalTime = row[OrienteeringResults.totalTime],
            rank = row[OrienteeringResults.rank],
            status = row[OrienteeringResults.status],
            penaltyTime = row[OrienteeringResults.penaltyTime],
            splits = splits,
            isEditable = row[OrienteeringResults.isEditable],
            isEdited = row[OrienteeringResults.isEdited]
        )
    }

    /**
     * Пересчитывает места для всех FINISHED-результатов группы.
     * Вызывается внутри транзакции сразу после upsert нового результата.
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
                OrienteeringResultResponse(
                    id = row[OrienteeringResults.id],
                    competitionId = row[OrienteeringResults.competitionId],
                    groupId = row[OrienteeringResults.groupId],
                    participantId = row[OrienteeringResults.participantId],
                    startTime = row[OrienteeringResults.startTime],
                    finishTime = row[OrienteeringResults.finishTime],
                    totalTime = row[OrienteeringResults.totalTime],
                    rank = row[OrienteeringResults.rank],
                    status = row[OrienteeringResults.status],
                    penaltyTime = row[OrienteeringResults.penaltyTime],
                    splits = splits,
                    isEditable = row[OrienteeringResults.isEditable],
                    isEdited = row[OrienteeringResults.isEdited]
                )
            }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
