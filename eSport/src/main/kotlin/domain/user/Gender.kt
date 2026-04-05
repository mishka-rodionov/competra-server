package com.sportenth.domain.user

import com.google.gson.annotations.SerializedName

enum class Gender {
    @SerializedName("male")
    MALE,

    @SerializedName("female")
    FEMALE
}