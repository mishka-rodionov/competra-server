package com.competra.data.response.rating

import com.google.gson.annotations.SerializedName

data class RatingStandingBreakdownEntry(
    @SerializedName("competitionId") val competitionId: String,
    @SerializedName("place") val place: Int?,
    @SerializedName("points") val points: Int
)

data class RatingStandingEntry(
    @SerializedName("participantKey") val participantKey: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("totalPoints") val totalPoints: Int,
    @SerializedName("rank") val rank: Int,
    @SerializedName("breakdown") val breakdown: List<RatingStandingBreakdownEntry>
)

data class RatingStandingsResponse(
    @SerializedName("ratingGroupId") val ratingGroupId: Long,
    @SerializedName("standings") val standings: List<RatingStandingEntry>
)
