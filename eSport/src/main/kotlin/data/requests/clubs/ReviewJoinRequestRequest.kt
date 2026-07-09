package com.competra.data.requests.clubs

import com.google.gson.annotations.SerializedName

data class ReviewJoinRequestRequest(
    @SerializedName("approve") val approve: Boolean
)
