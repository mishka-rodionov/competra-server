package com.competra.data.response.rating

import com.google.gson.annotations.SerializedName

data class RatingCompetitionResponse(
    @SerializedName("id") val id: String,
    @SerializedName("ratingId") val ratingId: String,
    @SerializedName("competitionId") val competitionId: String,
    @SerializedName("competitionTitle") val competitionTitle: String,
    @SerializedName("competitionStartDate") val competitionStartDate: Long,
    @SerializedName("addedAt") val addedAt: Long
)
