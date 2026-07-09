package com.competra.data.requests.rating

import com.google.gson.annotations.SerializedName

data class CreateRatingRequest(
    @SerializedName("name") val name: String,
    @SerializedName("groups") val groups: List<RatingGroupRequest>
)
