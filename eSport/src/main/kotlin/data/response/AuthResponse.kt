package com.competra.data.response

import com.google.gson.annotations.SerializedName
import com.competra.data.response.user.UserResponse

data class AuthResponse(
    @SerializedName("user")
    val user: UserResponse,
    @SerializedName("token")
    val token: TokenResponse
)