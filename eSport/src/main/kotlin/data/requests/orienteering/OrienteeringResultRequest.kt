package com.competra.data.requests.orienteering

import com.google.gson.annotations.SerializedName

data class OrienteeringResultRequest(
    @SerializedName("id") val id: String,
    @SerializedName("competitionId") val competitionId: String,
    @SerializedName("groupId") val groupId: Long,
    @SerializedName("participantId") val participantId: String,
    @SerializedName("startTime") val startTime: Long?,
    @SerializedName("finishTime") val finishTime: Long?,
    @SerializedName("totalTime") val totalTime: Long?,
    @SerializedName("rank") val rank: Int?,
    @SerializedName("status") val status: String,
    @SerializedName("penaltyTime") val penaltyTime: Long,
    @SerializedName("totalScore") val totalScore: Int? = null,
    @SerializedName("scorePenalty") val scorePenalty: Int = 0,
    @SerializedName("splits") val splits: List<SplitTimeRequest>?,
    @SerializedName("isEditable") val isEditable: Boolean,
    @SerializedName("isEdited") val isEdited: Boolean,
    @SerializedName("serverUpdatedAt") val serverUpdatedAt: Long? = null
)

data class SplitTimeRequest(
    @SerializedName("controlPoint") val controlPoint: Int,
    @SerializedName("timestamp") val timestamp: Long
)
