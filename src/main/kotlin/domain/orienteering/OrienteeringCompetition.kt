package com.sportenth.domain.orienteering

import com.rodionov.domain.models.Competition

data class OrienteeringCompetition(
    val competitionId: Long,
    val competition: Competition,
    val direction: OrienteeringDirection
)
