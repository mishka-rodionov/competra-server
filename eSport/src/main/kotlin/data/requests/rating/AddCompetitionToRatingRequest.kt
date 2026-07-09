package com.competra.data.requests.rating

import com.google.gson.annotations.SerializedName

data class AddCompetitionToRatingRequest(
    @SerializedName("competitionId") val competitionId: String
)
