package com.sportenth.domain.orienteering

import com.sportenth.domain.Competition

data class OrienteeringCompetition(
    val competitionId: Long,
    val competition: Competition,
    val direction: OrienteeringDirection
)
