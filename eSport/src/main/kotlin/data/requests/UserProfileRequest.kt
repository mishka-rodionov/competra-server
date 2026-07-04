package com.competra.data.requests

import com.google.gson.annotations.SerializedName

data class UserProfileRequest(
    @SerializedName("first_name") val firstName: String? = null,
    @SerializedName("last_name") val lastName: String? = null,
    @SerializedName("middle_name") val middleName: String? = null,
    @SerializedName("birth_date") val birthDate: String? = null,
    @SerializedName("phone_number") val phoneNumber: String? = null,
    @SerializedName("avatar_url") val avatarUrl: String? = null,
    @SerializedName("avatar_crop_x") val avatarCropX: Double? = null,
    @SerializedName("avatar_crop_y") val avatarCropY: Double? = null,
    @SerializedName("avatar_crop_width") val avatarCropWidth: Double? = null,
    @SerializedName("avatar_crop_height") val avatarCropHeight: Double? = null
)
