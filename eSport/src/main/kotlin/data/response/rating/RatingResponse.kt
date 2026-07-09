package com.competra.data.response.rating

import com.google.gson.annotations.SerializedName

data class RatingResponse(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("ownerClubId") val ownerClubId: String,
    @SerializedName("groups") val groups: List<RatingGroupResponse>,
    @SerializedName("createdAt") val createdAt: Long,
    @SerializedName("updatedAt") val updatedAt: Long
)
