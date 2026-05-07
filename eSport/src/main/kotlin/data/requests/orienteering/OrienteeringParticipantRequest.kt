package com.sportenth.data.requests.orienteering

import com.google.gson.annotations.SerializedName

data class OrienteeringParticipantRequest(
    @SerializedName("id") val id: String,
    @SerializedName("userId") val userId: String?,
    @SerializedName("firstName") val firstName: String,
    @SerializedName("lastName") val lastName: String,
    @SerializedName("groupId") val groupId: Long,
    @SerializedName("groupName") val groupName: String,
    @SerializedName("competitionId") val competitionId: Long,
    @SerializedName("commandName") val commandName: String?,
    @SerializedName("startNumber") val startNumber: Int,
    @SerializedName("startTime") val startTime: Long,
    @SerializedName("chipNumber") val chipNumber: Long,
    @SerializedName("comment") val comment: String?,
    @SerializedName("isChipGiven") val isChipGiven: Boolean,
    @SerializedName("serverUpdatedAt") val serverUpdatedAt: Long? = null
)
