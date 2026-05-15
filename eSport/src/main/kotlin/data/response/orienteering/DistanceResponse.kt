package com.competra.data.response.orienteering

import com.google.gson.annotations.SerializedName

data class DistanceResponse(
    @SerializedName("id") val id: Long,
    @SerializedName("competitionId") val competitionId: Long,
    @SerializedName("name") val name: String?,
    @SerializedName("lengthMeters") val lengthMeters: Int,
    @SerializedName("climbMeters") val climbMeters: Int,
    @SerializedName("controlsCount") val controlsCount: Int,
    @SerializedName("description") val description: String?,
    @SerializedName("controlPoints") val controlPoints: List<ControlPointResponse> = emptyList(),
    @SerializedName("updatedAt") val updatedAt: Long = 0L
)
