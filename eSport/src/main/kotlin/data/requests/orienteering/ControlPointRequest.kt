package com.sportenth.data.requests.orienteering

import com.google.gson.annotations.SerializedName

data class ControlPointRequest(
    @SerializedName("number") val number: Int,
    @SerializedName("role") val role: String = "ORDINARY",
    @SerializedName("score") val score: Int = 0
)
