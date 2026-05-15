package com.competra.data.response.user

import com.google.gson.annotations.SerializedName
import com.competra.domain.user.Gender

data class UserResponse(
    @SerializedName("id")
    val id: String,
    @SerializedName("first_name")
    val firstName: String,
    @SerializedName("last_name")
    val lastName: String,
    @SerializedName("middle_name")
    val middleName: String?, // отчество
    @SerializedName("birth_date")
    val birthDate: String,
    @SerializedName("gender")
    val gender: Gender,
    @SerializedName("avatar_url")
    val avatarUrl: String,
    @SerializedName("phone_number")
    val phoneNumber: String?,
    @SerializedName("email")
    val email: String,
    @SerializedName("qualification")
    val qualification: List<QualificationResponse>
)
