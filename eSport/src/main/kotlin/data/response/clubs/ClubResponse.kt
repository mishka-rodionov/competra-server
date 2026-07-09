package com.competra.data.response.clubs

import com.google.gson.annotations.SerializedName

data class ClubResponse(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String?,
    @SerializedName("allowJoinRequests") val allowJoinRequests: Boolean,
    @SerializedName("foundedAt") val foundedAt: Long,
    @SerializedName("membersCount") val membersCount: Int,
    @SerializedName("updatedAt") val updatedAt: Long
)
