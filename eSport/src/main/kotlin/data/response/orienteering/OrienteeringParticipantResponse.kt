package com.competra.data.response.orienteering

import com.google.gson.annotations.SerializedName

data class OrienteeringParticipantResponse(
    @SerializedName("id") val id: String,
    @SerializedName("userId") val userId: String?,
    @SerializedName("firstName") val firstName: String,
    @SerializedName("lastName") val lastName: String,
    @SerializedName("groupId") val groupId: Long,
    @SerializedName("groupName") val groupName: String,
    @SerializedName("competitionId") val competitionId: String,
    @SerializedName("commandName") val commandName: String?,
    @SerializedName("startNumber") val startNumber: Int,
    @SerializedName("startTime") val startTime: Long,
    @SerializedName("chipNumber") val chipNumber: Long,
    @SerializedName("comment") val comment: String?,
    @SerializedName("isChipGiven") val isChipGiven: Boolean,
    /** Участник вне конкурса (ВК): результат без места, не идёт в командный зачёт и рейтинг. */
    @SerializedName("isOutOfCompetition") val isOutOfCompetition: Boolean = false,
    @SerializedName("updatedAt") val updatedAt: Long = 0L
)
