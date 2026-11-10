package com.competra.data.requests.orienteering

import com.google.gson.annotations.SerializedName

data class OrienteeringParticipantRequest(
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
    /**
     * Участник вне конкурса (ВК). null — не менять (старые клиенты поле не присылают
     * и не должны сбрасывать флаг); для нового участника null означает false.
     */
    @SerializedName("isOutOfCompetition") val isOutOfCompetition: Boolean? = null,
    @SerializedName("serverUpdatedAt") val serverUpdatedAt: Long? = null
)
