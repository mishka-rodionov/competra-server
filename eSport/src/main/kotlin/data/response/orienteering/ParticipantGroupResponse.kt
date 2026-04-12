package com.sportenth.data.response.orienteering

import com.google.gson.annotations.SerializedName

data class ParticipantGroupResponse(
    @SerializedName("groupId") val groupId: String,
    @SerializedName("competitionId") val competitionId: Long,
    @SerializedName("title") val title: String,
    @SerializedName("gender") val gender: String?,
    @SerializedName("minAge") val minAge: Int?,
    @SerializedName("maxAge") val maxAge: Int?,
    @SerializedName("distanceId") val distanceId: String,
    @SerializedName("maxParticipants") val maxParticipants: Int?
)
