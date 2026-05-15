package com.competra.domain

data class ParticipantGroup(
    val groupId: Long,
    val competitionId: Long,
    val title: String,
    val distance: Double,
    val countOfControls: Int,
    val maxTimeInMinute: Int
)