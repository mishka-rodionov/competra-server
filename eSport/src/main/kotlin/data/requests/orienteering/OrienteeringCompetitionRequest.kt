package com.sportenth.data.requests.orienteering

import com.google.gson.annotations.SerializedName

data class OrienteeringCompetitionRequest(
    @SerializedName("competitionId") val competitionId: String,
    @SerializedName("competition") val competition: CompetitionRequest,
    @SerializedName("direction") val direction: String,
    @SerializedName("punchingSystem") val punchingSystem: String,
    @SerializedName("startTimeMode") val startTimeMode: String,
    @SerializedName("countdownTimer") val countdownTimer: Long?,
    @SerializedName("startIntervalSeconds") val startIntervalSeconds: Int? = null,
    @SerializedName("serverUpdatedAt") val serverUpdatedAt: Long? = null
)
