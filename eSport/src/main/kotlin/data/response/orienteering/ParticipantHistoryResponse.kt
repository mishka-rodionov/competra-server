package com.competra.data.response.orienteering

import com.google.gson.annotations.SerializedName

/**
 * Одно участие человека в прошлом соревновании организатора — источник подсказок
 * при ручном добавлении участника (`GET /event/orienteering/participants/history`).
 */
data class ParticipantHistoryResponse(
    @SerializedName("id") val id: String,
    @SerializedName("userId") val userId: String?,
    @SerializedName("firstName") val firstName: String,
    @SerializedName("lastName") val lastName: String,
    @SerializedName("groupName") val groupName: String,
    @SerializedName("commandName") val commandName: String?,
    @SerializedName("competitionId") val competitionId: String,
    @SerializedName("competitionDate") val competitionDate: Long
)
