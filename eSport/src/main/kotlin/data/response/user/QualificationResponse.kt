package com.sportenth.data.response.user

import com.google.gson.annotations.SerializedName
import com.sportenth.domain.KindOfSport
import com.sportenth.domain.SportsCategory

data class QualificationResponse(
    @SerializedName("kind_of_sport")
    val kindOfSport: KindOfSport,
    @SerializedName("sports_category")
    val sportsCategory: SportsCategory
)