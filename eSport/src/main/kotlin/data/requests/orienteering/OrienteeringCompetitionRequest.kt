package com.competra.data.requests.orienteering

import com.google.gson.annotations.SerializedName

data class OrienteeringCompetitionRequest(
    @SerializedName("competitionId") val competitionId: String,
    @SerializedName("competition") val competition: CompetitionRequest,
    @SerializedName("direction") val direction: String,
    @SerializedName("punchingSystem") val punchingSystem: String,
    @SerializedName("startTimeMode") val startTimeMode: String,
    @SerializedName("countdownTimer") val countdownTimer: Long?,
    @SerializedName("startIntervalSeconds") val startIntervalSeconds: Int? = null,
    /** Контрольное время соревнования в минутах; группа может переопределить его своим timeLimitMinutes. */
    @SerializedName("controlTimeMinutes") val controlTimeMinutes: Int? = null,
    /** [com.competra.domain.orienteering.OvertimePolicy]: IGNORE / DISQUALIFY / SCORE_PENALTY. */
    @SerializedName("overtimePolicy") val overtimePolicy: String? = null,
    @SerializedName("serverUpdatedAt") val serverUpdatedAt: Long? = null
)
