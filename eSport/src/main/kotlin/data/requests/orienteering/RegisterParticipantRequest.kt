package com.competra.data.requests.orienteering

data class RegisterParticipantRequest(
    val competitionId: String,
    val groupId: Long,
    val firstName: String,
    val lastName: String,
    val commandName: String? = null
)
