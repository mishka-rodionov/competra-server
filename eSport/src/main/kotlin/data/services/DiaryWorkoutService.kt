package com.competra.data.services

import com.competra.data.database.entity.diary.BikeDetails
import com.competra.data.database.entity.diary.RunDetails
import com.competra.data.database.entity.diary.SkiDetails
import com.competra.data.database.entity.diary.Workouts
import com.competra.data.requests.diary.WorkoutRequest
import com.competra.data.response.diary.BikeDetailsResponse
import com.competra.data.response.diary.RunDetailsResponse
import com.competra.data.response.diary.SkiDetailsResponse
import com.competra.data.response.diary.WorkoutResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

/**
 * Тренировочный дневник — независимый домен без конфликт-резолвинга (в отличие от
 * orienteering-сервисов): тренировки всегда однопользовательские, поэтому upsert всегда
 * перезаписывает запись владельца (last-write-wins), без сравнения updatedAt и без 409.
 */
class DiaryWorkoutService {

    suspend fun upsertAll(userId: String, requests: List<WorkoutRequest>): List<WorkoutResponse> = dbQuery {
        val now = System.currentTimeMillis()
        requests.map { req -> upsertOne(userId, req, now) }
    }

    private fun upsertOne(userId: String, req: WorkoutRequest, now: Long): WorkoutResponse {
        val existing = req.workoutId?.let { id ->
            Workouts.selectAll().where { (Workouts.id eq id) and (Workouts.userId eq userId) }.singleOrNull()
        }

        val workoutId = if (existing != null) {
            Workouts.update({ (Workouts.id eq existing[Workouts.id]) and (Workouts.userId eq userId) }) {
                it[sportType] = req.sportType
                it[status] = req.status
                it[scheduledDate] = req.scheduledDate
                it[startedAt] = req.startedAt
                it[durationSeconds] = req.durationSeconds
                it[distanceMeters] = req.distanceMeters
                it[elevationGainMeters] = req.elevationGainMeters
                it[notes] = req.notes
                it[track] = req.trackEncoded
                it[updatedAt] = now
            }
            existing[Workouts.id]
        } else {
            Workouts.insert {
                it[Workouts.userId] = userId
                it[sportType] = req.sportType
                it[status] = req.status
                it[scheduledDate] = req.scheduledDate
                it[startedAt] = req.startedAt
                it[durationSeconds] = req.durationSeconds
                it[distanceMeters] = req.distanceMeters
                it[elevationGainMeters] = req.elevationGainMeters
                it[notes] = req.notes
                it[track] = req.trackEncoded
                it[updatedAt] = now
            } get Workouts.id
        }

        upsertDetails(workoutId, req)

        return Workouts.selectAll().where { Workouts.id eq workoutId }.single().toResponse()
    }

    private fun upsertDetails(workoutId: Long, req: WorkoutRequest) {
        @Suppress("DEPRECATION")
        RunDetails.deleteWhere { RunDetails.workoutId eq workoutId }
        @Suppress("DEPRECATION")
        BikeDetails.deleteWhere { BikeDetails.workoutId eq workoutId }
        @Suppress("DEPRECATION")
        SkiDetails.deleteWhere { SkiDetails.workoutId eq workoutId }

        req.runDetails?.let { details ->
            RunDetails.insert {
                it[RunDetails.workoutId] = workoutId
                it[cadenceSpm] = details.cadenceSpm
            }
        }
        req.bikeDetails?.let { details ->
            BikeDetails.insert {
                it[BikeDetails.workoutId] = workoutId
                it[cadenceRpm] = details.cadenceRpm
                it[powerWatts] = details.powerWatts
            }
        }
        req.skiDetails?.let { details ->
            SkiDetails.insert {
                it[SkiDetails.workoutId] = workoutId
                it[style] = details.style
            }
        }
    }

    suspend fun getByUser(userId: String): List<WorkoutResponse> = dbQuery {
        Workouts.selectAll().where { Workouts.userId eq userId }.map { it.toResponse() }
    }

    suspend fun deleteById(id: Long, userId: String): Boolean = dbQuery {
        @Suppress("DEPRECATION")
        Workouts.deleteWhere { (Workouts.id eq id) and (Workouts.userId eq userId) } > 0
    }

    private fun ResultRow.toResponse(): WorkoutResponse {
        val workoutId = this[Workouts.id]
        val run = RunDetails.selectAll().where { RunDetails.workoutId eq workoutId }.singleOrNull()
        val bike = BikeDetails.selectAll().where { BikeDetails.workoutId eq workoutId }.singleOrNull()
        val ski = SkiDetails.selectAll().where { SkiDetails.workoutId eq workoutId }.singleOrNull()

        return WorkoutResponse(
            id = workoutId,
            sportType = this[Workouts.sportType],
            status = this[Workouts.status],
            scheduledDate = this[Workouts.scheduledDate],
            startedAt = this[Workouts.startedAt],
            durationSeconds = this[Workouts.durationSeconds],
            distanceMeters = this[Workouts.distanceMeters],
            elevationGainMeters = this[Workouts.elevationGainMeters],
            notes = this[Workouts.notes],
            trackEncoded = this[Workouts.track],
            runDetails = run?.let { RunDetailsResponse(cadenceSpm = it[RunDetails.cadenceSpm]) },
            bikeDetails = bike?.let {
                BikeDetailsResponse(cadenceRpm = it[BikeDetails.cadenceRpm], powerWatts = it[BikeDetails.powerWatts])
            },
            skiDetails = ski?.let { SkiDetailsResponse(style = it[SkiDetails.style]) },
            updatedAt = this[Workouts.updatedAt]
        )
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
