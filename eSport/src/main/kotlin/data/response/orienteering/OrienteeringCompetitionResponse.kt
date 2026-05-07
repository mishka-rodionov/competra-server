package com.sportenth.data.response.orienteering

import com.google.gson.annotations.SerializedName

data class OrienteeringCompetitionResponse(
    @SerializedName("competitionId") val competitionId: String,
    @SerializedName("competition") val competition: CompetitionResponse,
    @SerializedName("direction") val direction: String,
    @SerializedName("punchingSystem") val punchingSystem: String,
    @SerializedName("startTimeMode") val startTimeMode: String,
    @SerializedName("countdownTimer") val countdownTimer: Long?,
    @SerializedName("startTime") val startTime: Long?,
    @SerializedName("startIntervalSeconds") val startIntervalSeconds: Int? = null,
    @SerializedName("updatedAt") val updatedAt: Long = 0L
)
