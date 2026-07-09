package com.competra.data.requests.clubs

import com.google.gson.annotations.SerializedName

data class CreateClubRequest(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String?,
    @SerializedName("allowJoinRequests") val allowJoinRequests: Boolean = true
)
