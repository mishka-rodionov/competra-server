package com.competra.domain

import java.time.LocalDate

data class Competition(
    val title: String,
    val date: LocalDate,
    val kindOfSport: KindOfSport,
    val description: String,
    val address: String,
    val coordinates: Coordinates,
)