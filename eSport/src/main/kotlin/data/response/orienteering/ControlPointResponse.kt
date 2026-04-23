package com.sportenth.data.response.orienteering

import com.google.gson.annotations.SerializedName

data class ControlPointResponse(
    @SerializedName("number") val number: Int,
    @SerializedName("role") val role: String = "ORDINARY",
    @SerializedName("score") val score: Int = 0
)
