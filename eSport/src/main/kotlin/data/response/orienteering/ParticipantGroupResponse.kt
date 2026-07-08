package com.competra.data.response.orienteering

import com.google.gson.annotations.SerializedName

data class ParticipantGroupResponse(
    @SerializedName("groupId") val groupId: Long,
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
    @SerializedName("updatedAt") val updatedAt: Long = 0L
)
