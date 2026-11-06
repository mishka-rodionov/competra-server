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

/** Организатор сам привязывает вручную внесённых участников к аккаунту [userId]. */
data class OrganizerLinkParticipantsRequest(
    @SerializedName("userId") val userId: String,
    @SerializedName("participantIds") val participantIds: List<String>
)

/** Организатор приводит к одному написанию ФИО непривязанных участников (дубли «Родионов М.» / «Родионов Михаил»). */
data class RenameParticipantsRequest(
    @SerializedName("participantIds") val participantIds: List<String>,
    @SerializedName("firstName") val firstName: String,
    @SerializedName("lastName") val lastName: String
)
