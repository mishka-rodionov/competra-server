package com.competra.data.response.user

import com.google.gson.annotations.SerializedName
import com.competra.domain.KindOfSport
import com.competra.domain.SportsCategory

data class QualificationResponse(
    @SerializedName("kind_of_sport")
    val kindOfSport: KindOfSport,
    @SerializedName("sports_category")
    val sportsCategory: SportsCategory
)