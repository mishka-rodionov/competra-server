package com.competra.data.response.diary

import com.google.gson.annotations.SerializedName

data class WorkoutResponse(
    @SerializedName("id") val id: Long,
    @SerializedName("sportType") val sportType: String,
    @SerializedName("status") val status: String,
    @SerializedName("scheduledDate") val scheduledDate: Long?,
    @SerializedName("startedAt") val startedAt: Long?,
    @SerializedName("durationSeconds") val durationSeconds: Int?,
    @SerializedName("distanceMeters") val distanceMeters: Int?,
    @SerializedName("elevationGainMeters") val elevationGainMeters: Int?,
    @SerializedName("notes") val notes: String?,
    @SerializedName("runDetails") val runDetails: RunDetailsResponse? = null,
    @SerializedName("bikeDetails") val bikeDetails: BikeDetailsResponse? = null,
    @SerializedName("skiDetails") val skiDetails: SkiDetailsResponse? = null,
    @SerializedName("updatedAt") val updatedAt: Long = 0L
)

data class RunDetailsResponse(
    @SerializedName("cadenceSpm") val cadenceSpm: Int?
)

data class BikeDetailsResponse(
    @SerializedName("cadenceRpm") val cadenceRpm: Int?,
    @SerializedName("powerWatts") val powerWatts: Int?
)

data class SkiDetailsResponse(
    @SerializedName("style") val style: String
)
