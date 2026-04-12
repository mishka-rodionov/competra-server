package com.sportenth.data.requests.orienteering

data class RegisterParticipantRequest(
    val competitionId: Long,
    val groupId: String,
    val firstName: String,
    val lastName: String
)
