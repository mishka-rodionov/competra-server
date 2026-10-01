package com.competra.data.requests.orienteering

import com.google.gson.annotations.SerializedName

data class CreateParticipantLinkRequest(
    @SerializedName("participantIds") val participantIds: List<String>,
    /** SUGGESTION | MANUAL */
    @SerializedName("source") val source: String
)

data class ReviewParticipantLinkRequest(
    @SerializedName("approve") val approve: Boolean,
    @SerializedName("comment") val comment: String? = null
)
