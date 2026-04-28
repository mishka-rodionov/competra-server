package com.sportenth.data.services

import com.sportenth.data.database.entity.OrienteeringResults
import com.sportenth.data.database.entity.SplitTimes
import com.sportenth.data.requests.orienteering.OrienteeringResultRequest
import com.sportenth.data.response.orienteering.OrienteeringResultResponse
import com.sportenth.data.response.orienteering.SplitTimeResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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
