package com.sportenth.data.requests.orienteering

import com.google.gson.annotations.SerializedName

data class OrienteeringResultRequest(
    @SerializedName("id") val id: String,
    @SerializedName("competitionId") val competitionId: Long,
    @SerializedName("groupId") val groupId: Long,
    @SerializedName("participantId") val participantId: String,
    @SerializedName("startTime") val startTime: Long?,
    @SerializedName("finishTime") val finishTime: Long?,
    @SerializedName("totalTime") val totalTime: Long?,
    @SerializedName("rank") val rank: Int?,
    @SerializedName("status") val status: String,
    @SerializedName("penaltyTime") val penaltyTime: Long,
    @SerializedName("splits") val splits: List<SplitTimeRequest>?,
    @SerializedName("isEditable") val isEditable: Boolean,
    @SerializedName("isEdited") val isEdited: Boolean
)

data class SplitTimeRequest(
    @SerializedName("controlPoint") val controlPoint: Int,
    @SerializedName("timestamp") val timestamp: Long
)
