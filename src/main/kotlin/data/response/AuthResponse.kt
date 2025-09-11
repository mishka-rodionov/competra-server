package com.sportenth.data.response

import com.google.gson.annotations.SerializedName
import com.sportenth.data.response.user.UserResponse

data class AuthResponse(
    @SerializedName("user")
    val user: UserResponse,
    @SerializedName("token")
    val token: TokenResponse
)