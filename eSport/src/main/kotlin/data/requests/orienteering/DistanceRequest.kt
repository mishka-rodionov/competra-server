package com.competra.data.requests.orienteering

import com.google.gson.annotations.SerializedName

data class DistanceRequest(
    @SerializedName("distanceId") val distanceId: Long?,
    @SerializedName("competitionId") val competitionId: String,
    @SerializedName("name") val name: String?,
    @SerializedName("lengthMeters") val lengthMeters: Int,
    @SerializedName("climbMeters") val climbMeters: Int,
    @SerializedName("controlsCount") val controlsCount: Int,
    @SerializedName("description") val description: String?,
    @SerializedName("controlPoints") val controlPoints: List<ControlPointRequest> = emptyList(),
    @SerializedName("finishControlPoint") val finishControlPoint: Int? = null,
    @SerializedName("startControlPoint") val startControlPoint: Int? = null,
    @SerializedName("mapUrl") val mapUrl: String? = null,
    @SerializedName("mapTopLeftLat") val mapTopLeftLat: Double? = null,
    @SerializedName("mapTopLeftLng") val mapTopLeftLng: Double? = null,
    @SerializedName("mapTopRightLat") val mapTopRightLat: Double? = null,
    @SerializedName("mapTopRightLng") val mapTopRightLng: Double? = null,
    @SerializedName("mapBottomRightLat") val mapBottomRightLat: Double? = null,
    @SerializedName("mapBottomRightLng") val mapBottomRightLng: Double? = null,
    @SerializedName("serverUpdatedAt") val serverUpdatedAt: Long? = null
)
