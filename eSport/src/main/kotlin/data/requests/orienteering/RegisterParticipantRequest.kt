package com.sportenth.data.requests.orienteering

data class RegisterParticipantRequest(
    val competitionId: String,
    val groupId: String,
    val firstName: String,
    val lastName: String
)
