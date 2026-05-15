package com.competra.data.requests.orienteering

import com.google.gson.annotations.SerializedName

data class ParticipantGroupRequest(
    @SerializedName("groupId") val groupId: Long?,
    @SerializedName("competitionId") val competitionId: Long,
    @SerializedName("title") val title: String,
    @SerializedName("gender") val gender: String?,
    @SerializedName("minAge") val minAge: Int?,
    @SerializedName("maxAge") val maxAge: Int?,
    @SerializedName("distanceId") val distanceId: Long,
    @SerializedName("maxParticipants") val maxParticipants: Int?,
    @SerializedName("serverUpdatedAt") val serverUpdatedAt: Long? = null
)
