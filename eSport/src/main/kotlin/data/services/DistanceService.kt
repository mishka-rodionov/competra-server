package com.sportenth.data.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sportenth.data.database.entity.Distances
import com.sportenth.data.requests.orienteering.ControlPointRequest
import com.sportenth.data.requests.orienteering.DistanceRequest
import com.sportenth.data.response.orienteering.ControlPointResponse
import com.sportenth.data.response.orienteering.DistanceResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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
        requests.map { req ->
            val cpJson = serializeControlPoints(req.controlPoints)

            if (req.distanceId != null) {
                val existing = Distances.selectAll()
                    .where { Distances.id eq req.distanceId }
                    .singleOrNull()

                if (existing != null) {
                    Distances.update({ Distances.id eq req.distanceId }) {
                        it[competitionId] = req.competitionId
                        it[name] = req.name
                        it[lengthMeters] = req.lengthMeters
                        it[climbMeters] = req.climbMeters
                        it[controlsCount] = req.controlsCount
                        it[description] = req.description
                        it[controlPoints] = cpJson
                    }
                    val row = Distances.selectAll().where { Distances.id eq req.distanceId }.single()
                    return@map DistanceResponse(
                        id = row[Distances.id],
                        competitionId = row[Distances.competitionId],
                        name = row[Distances.name],
                        lengthMeters = row[Distances.lengthMeters],
                        climbMeters = row[Distances.climbMeters],
                        controlsCount = row[Distances.controlsCount],
                        description = row[Distances.description],
                        controlPoints = deserializeControlPoints(row[Distances.controlPoints])
                    )
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
            } get Distances.id

            val row = Distances.selectAll().where { Distances.id eq newId }.single()
            DistanceResponse(
                id = row[Distances.id],
                competitionId = row[Distances.competitionId],
                name = row[Distances.name],
                lengthMeters = row[Distances.lengthMeters],
                climbMeters = row[Distances.climbMeters],
                controlsCount = row[Distances.controlsCount],
                description = row[Distances.description],
                controlPoints = deserializeControlPoints(row[Distances.controlPoints])
            )
        }
    }

    suspend fun getByCompetition(competitionId: Long): List<DistanceResponse> = dbQuery {
        Distances.selectAll()
            .where { Distances.competitionId eq competitionId }
            .map { row ->
                DistanceResponse(
                    id = row[Distances.id],
                    competitionId = row[Distances.competitionId],
                    name = row[Distances.name],
                    lengthMeters = row[Distances.lengthMeters],
                    climbMeters = row[Distances.climbMeters],
                    controlsCount = row[Distances.controlsCount],
                    description = row[Distances.description],
                    controlPoints = deserializeControlPoints(row[Distances.controlPoints])
                )
            }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
