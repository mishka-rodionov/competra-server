package com.competra.data.requests.orienteering

import com.google.gson.annotations.SerializedName

data class ParticipantGroupRequest(
    @SerializedName("groupId") val groupId: Long?,
    @SerializedName("competitionId") val competitionId: String,
    @SerializedName("title") val title: String,
    @SerializedName("gender") val gender: String?,
    @SerializedName("minAge") val minAge: Int?,
    @SerializedName("maxAge") val maxAge: Int?,
    @SerializedName("distanceId") val distanceId: Long,
    @SerializedName("maxParticipants") val maxParticipants: Int?,
    @SerializedName("timeLimitMinutes") val timeLimitMinutes: Int? = null,
    @SerializedName("scorePenaltyPerMinute") val scorePenaltyPerMinute: Int? = null,
    @SerializedName("maxLatenessMinutes") val maxLatenessMinutes: Int? = null,
    /**
     * Своё N командного зачёта: N > 0 — задать, 0 — как у соревнования, null — не менять
     * (старые клиенты поле не знают и затёрли бы значение).
     */
    @SerializedName("teamCountedResults") val teamCountedResults: Int? = null,
    @SerializedName("serverUpdatedAt") val serverUpdatedAt: Long? = null
)
