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
    @SerializedName("avatar_crop_x")
    val avatarCropX: Double?,
    @SerializedName("avatar_crop_y")
    val avatarCropY: Double?,
    @SerializedName("avatar_crop_width")
    val avatarCropWidth: Double?,
    @SerializedName("avatar_crop_height")
    val avatarCropHeight: Double?,
    @SerializedName("phone_number")
    val phoneNumber: String?,
    @SerializedName("email")
    val email: String,
    @SerializedName("qualification")
    val qualification: List<QualificationResponse>
)
