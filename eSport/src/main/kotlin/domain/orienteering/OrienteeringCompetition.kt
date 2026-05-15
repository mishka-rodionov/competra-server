package com.competra.domain.orienteering

import com.competra.domain.Competition

data class OrienteeringCompetition(
    val competitionId: Long,
    val competition: Competition,
    val direction: OrienteeringDirection
)
