package com.competra.data.database.entity.diary

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/** Тренировочный дневник — независимый домен, не связан с Competitions. */
object Workouts : Table("workouts") {
    val id = long("id").autoIncrement()
    val userId = varchar("user_id", 200)
    val sportType = varchar("sport_type", 20)
    val status = varchar("status", 20)
    val scheduledDate = long("scheduled_date").nullable()
    val startedAt = long("started_at").nullable()
    val durationSeconds = integer("duration_seconds").nullable()
    val distanceMeters = integer("distance_meters").nullable()
    val elevationGainMeters = integer("elevation_gain_meters").nullable()
    val notes = varchar("notes", 1000).nullable()
    val updatedAt = long("updated_at").default(0L)

    override val primaryKey = PrimaryKey(id)
}

object RunDetails : Table("run_details") {
    val workoutId = long("workout_id").references(Workouts.id, onDelete = ReferenceOption.CASCADE)
    val cadenceSpm = integer("cadence_spm").nullable()

    override val primaryKey = PrimaryKey(workoutId)
}

object BikeDetails : Table("bike_details") {
    val workoutId = long("workout_id").references(Workouts.id, onDelete = ReferenceOption.CASCADE)
    val cadenceRpm = integer("cadence_rpm").nullable()
    val powerWatts = integer("power_watts").nullable()

    override val primaryKey = PrimaryKey(workoutId)
}

object SkiDetails : Table("ski_details") {
    val workoutId = long("workout_id").references(Workouts.id, onDelete = ReferenceOption.CASCADE)
    val style = varchar("style", 20)

    override val primaryKey = PrimaryKey(workoutId)
}
