package com.sportenth.data.requests.orienteering

import com.google.gson.annotations.SerializedName

data class ParticipantGroupRequest(
    @SerializedName("groupId") val groupId: String?,
    @SerializedName("competitionId") val competitionId: String,
    @SerializedName("title") val title: String,
    @SerializedName("gender") val gender: String?,
    @SerializedName("minAge") val minAge: Int?,
    @SerializedName("maxAge") val maxAge: Int?,
    @SerializedName("distanceId") val distanceId: String,
    @SerializedName("maxParticipants") val maxParticipants: Int?
)
