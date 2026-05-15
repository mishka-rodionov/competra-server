package com.competra.data.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.competra.data.database.entity.Distances
import com.competra.data.exception.ConflictException
import com.competra.data.requests.orienteering.ControlPointRequest
import com.competra.data.requests.orienteering.DistanceRequest
import com.competra.data.response.orienteering.ControlPointResponse
import com.competra.data.response.orienteering.DistanceResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class DistanceService {

    private val gson = Gson()
    private val cpListType = object : TypeToken<List<ControlPointResponse>>() {}.type

    private fun serializeControlPoints(points: List<ControlPointRequest>): String =
        gson.toJson(points)

    private fun deserializeControlPoints(json: String?): List<ControlPointResponse> =
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson(json, cpListType) ?: emptyList()

    suspend fun upsertAll(requests: List<DistanceRequest>): List<DistanceResponse> = dbQuery {
        val now = System.currentTimeMillis()
        requests.map { req ->
            val cpJson = serializeControlPoints(req.controlPoints)

            if (req.distanceId != null) {
                val existing = Distances.selectAll()
                    .where { Distances.id eq req.distanceId }
                    .singleOrNull()

                if (existing != null && req.serverUpdatedAt != null &&
                    req.serverUpdatedAt < existing[Distances.updatedAt]
                ) {
                    throw ConflictException(existing.toResponse())
                }

                if (existing != null) {
                    Distances.update({ Distances.id eq req.distanceId }) {
                        it[competitionId] = req.competitionId
                        it[name] = req.name
                        it[lengthMeters] = req.lengthMeters
                        it[climbMeters] = req.climbMeters
                        it[controlsCount] = req.controlsCount
                        it[description] = req.description
                        it[controlPoints] = cpJson
                        it[updatedAt] = now
                    }
                    return@map Distances.selectAll().where { Distances.id eq req.distanceId }.single().toResponse()
                }
            }

            val newId = Distances.insert {
                it[competitionId] = req.competitionId
                it[name] = req.name
                it[lengthMeters] = req.lengthMeters
                it[climbMeters] = req.climbMeters
                it[controlsCount] = req.controlsCount
                it[description] = req.description
                it[controlPoints] = cpJson
                it[updatedAt] = now
            } get Distances.id

            Distances.selectAll().where { Distances.id eq newId }.single().toResponse()
        }
    }

    suspend fun getByCompetition(competitionId: Long): List<DistanceResponse> = dbQuery {
        Distances.selectAll()
            .where { Distances.competitionId eq competitionId }
            .map { it.toResponse() }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun deleteById(id: Long): Boolean = dbQuery {
        @Suppress("DEPRECATION")
        Distances.deleteWhere { Distances.id eq id } > 0
    }

    private fun ResultRow.toResponse() = DistanceResponse(
        id = this[Distances.id],
        competitionId = this[Distances.competitionId],
        name = this[Distances.name],
        lengthMeters = this[Distances.lengthMeters],
        climbMeters = this[Distances.climbMeters],
        controlsCount = this[Distances.controlsCount],
        description = this[Distances.description],
        controlPoints = deserializeControlPoints(this[Distances.controlPoints]),
        updatedAt = this[Distances.updatedAt]
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
