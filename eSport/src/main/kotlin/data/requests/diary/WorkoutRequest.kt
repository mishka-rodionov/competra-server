package com.competra.data.requests.diary

import com.google.gson.annotations.SerializedName

/**
 * Запрос на создание/обновление тренировки дневника. Синк упрощённый (без
 * конфликт-резолвинга) — сервер всегда перезаписывает запись, ориентируясь на [workoutId].
 */
data class WorkoutRequest(
    @SerializedName("workoutId") val workoutId: Long?,
    @SerializedName("sportType") val sportType: String,
    @SerializedName("status") val status: String,
    @SerializedName("scheduledDate") val scheduledDate: Long?,
    @SerializedName("startedAt") val startedAt: Long?,
    @SerializedName("durationSeconds") val durationSeconds: Int?,
    @SerializedName("distanceMeters") val distanceMeters: Int?,
    @SerializedName("elevationGainMeters") val elevationGainMeters: Int?,
    @SerializedName("notes") val notes: String?,
    @SerializedName("runDetails") val runDetails: RunDetailsRequest? = null,
    @SerializedName("bikeDetails") val bikeDetails: BikeDetailsRequest? = null,
    @SerializedName("skiDetails") val skiDetails: SkiDetailsRequest? = null
)

data class RunDetailsRequest(
    @SerializedName("cadenceSpm") val cadenceSpm: Int?
)

data class BikeDetailsRequest(
    @SerializedName("cadenceRpm") val cadenceRpm: Int?,
    @SerializedName("powerWatts") val powerWatts: Int?
)

data class SkiDetailsRequest(
    @SerializedName("style") val style: String
)
