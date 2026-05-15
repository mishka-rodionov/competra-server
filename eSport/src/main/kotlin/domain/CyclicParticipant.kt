package com.competra.domain

data class CyclicParticipant(
    override val id: String,
    override val userId: String,
    val startPlace: Int,
    val startTime: Long,
    val group: String
): Participant
