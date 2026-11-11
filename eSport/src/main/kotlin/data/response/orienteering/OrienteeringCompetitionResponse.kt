package com.competra.data.response.orienteering

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
    /** Контрольное время соревнования в минутах; группа может переопределить его своим timeLimitMinutes. */
    @SerializedName("controlTimeMinutes") val controlTimeMinutes: Int? = null,
    /**
     * [com.competra.domain.orienteering.OvertimePolicy]: IGNORE / DISQUALIFY / SCORE_PENALTY — политика
     * для формата соревнования по умолчанию (legacy, для клиентов без формата на дистанции).
     */
    @SerializedName("overtimePolicy") val overtimePolicy: String = "IGNORE",
    /** Политика КВ для дистанций, где места по времени: IGNORE / DISQUALIFY. */
    @SerializedName("timeOvertimePolicy") val timeOvertimePolicy: String = "IGNORE",
    /** Политика КВ для дистанций с баллами: IGNORE / DISQUALIFY / SCORE_PENALTY. */
    @SerializedName("scoreOvertimePolicy") val scoreOvertimePolicy: String = "SCORE_PENALTY",
    /** [com.competra.domain.orienteering.ByChoiceMode]: SCORE / MIN_CONTROLS (значим только для BY_CHOICE). */
    @SerializedName("byChoiceMode") val byChoiceMode: String = "SCORE",
    /** Настройки командного зачёта; null — зачёта нет. */
    @SerializedName("teamScoring") val teamScoring: TeamScoringResponse? = null,
    /** Режим «Регистрация на финише»: участники заводятся при считывании чипа на финише. */
    @SerializedName("isFinishRegistration") val isFinishRegistration: Boolean = false,
    /** [com.competra.domain.orienteering.DrawMode] проведённой жеребьёвки; null — не проводилась. */
    @SerializedName("drawMode") val drawMode: String? = null,
    /** Коридоры жеребьёвки по дистанциям. */
    @SerializedName("drawCorridors") val drawCorridors: Int? = null,
    /** Зазор жеребьёвки по дистанциям (в стартовых интервалах). */
    @SerializedName("drawGap") val drawGap: Int? = null,
    @SerializedName("updatedAt") val updatedAt: Long = 0L
)
