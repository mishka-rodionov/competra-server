package com.sportenth.data.response.orienteering

data class ParticipantGroupDetailResponse(
    val groupId: String,
    val title: String,
    val maxParticipants: Int?,
    val registeredCount: Int
)

data class CompetitionDetailResponse(
    val remoteId: String,
    val title: String,
    val startDate: Long,
    val endDate: Long?,
    val kindOfSport: String,
    val description: String?,
    val address: String?,
    val mainOrganizerId: String?,
    val coordinates: CoordinatesResponse?,
    val status: String,
    val registrationStart: Long?,
    val registrationEnd: Long?,
    val maxParticipants: Int?,
    val feeAmount: Double?,
    val feeCurrency: String?,
    val regulationUrl: String?,
    val mapUrl: String?,
    val contactPhone: String?,
    val contactEmail: String?,
    val website: String?,
    val resultsStatus: String,
    val participantGroups: List<ParticipantGroupDetailResponse>,
    val isUserRegistered: Boolean = false
)
