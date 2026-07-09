package com.competra.data.response.rating

import com.google.gson.annotations.SerializedName

data class RatingGroupMappingSuggestionResponse(
    @SerializedName("participantGroupId") val participantGroupId: Long,
    @SerializedName("participantGroupTitle") val participantGroupTitle: String,
    @SerializedName("suggestedRatingGroupId") val suggestedRatingGroupId: Long?,
    @SerializedName("confidence") val confidence: Float
)

data class AddCompetitionToRatingResponse(
    @SerializedName("ratingCompetition") val ratingCompetition: RatingCompetitionResponse,
    @SerializedName("groupMappingSuggestions") val groupMappingSuggestions: List<RatingGroupMappingSuggestionResponse>
)
