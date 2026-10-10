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
    /**
     * [com.competra.domain.orienteering.ByChoiceMode]: SCORE / MIN_CONTROLS. null — не менять
     * (старые клиенты поле не знают), для нового соревнования — SCORE.
     */
    @SerializedName("byChoiceMode") val byChoiceMode: String? = null,
    /** Командный зачёт: null — не менять, `enabled = false` — выключить. */
    @SerializedName("teamScoring") val teamScoring: TeamScoringRequest? = null,
    /**
     * [com.competra.domain.orienteering.DrawMode] проведённой жеребьёвки. null — не менять (старые
     * клиенты и веб, который жеребьёвку не проводит); вместе с ним сохраняются [drawCorridors] и [drawGap].
     */
    @SerializedName("drawMode") val drawMode: String? = null,
    @SerializedName("drawCorridors") val drawCorridors: Int? = null,
    @SerializedName("drawGap") val drawGap: Int? = null,
    @SerializedName("serverUpdatedAt") val serverUpdatedAt: Long? = null
)
