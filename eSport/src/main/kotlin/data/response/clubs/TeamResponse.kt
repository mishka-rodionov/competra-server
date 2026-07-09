package com.competra.data.response.clubs

import com.google.gson.annotations.SerializedName

data class TeamResponse(
    @SerializedName("id") val id: String,
    @SerializedName("clubId") val clubId: String,
    @SerializedName("name") val name: String,
    @SerializedName("sportType") val sportType: String,
    @SerializedName("membersCount") val membersCount: Int,
    @SerializedName("updatedAt") val updatedAt: Long
)
