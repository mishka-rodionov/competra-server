package com.sportenth.data.requests.orienteering

import com.google.gson.annotations.SerializedName

data class DistanceRequest(
    @SerializedName("distanceId") val distanceId: Long?,
    @SerializedName("competitionId") val competitionId: Long,
    @SerializedName("name") val name: String?,
    @SerializedName("lengthMeters") val lengthMeters: Int,
    @SerializedName("climbMeters") val climbMeters: Int,
    @SerializedName("controlsCount") val controlsCount: Int,
    @SerializedName("description") val description: String?,
    @SerializedName("controlPoints") val controlPoints: List<ControlPointRequest> = emptyList()
)
