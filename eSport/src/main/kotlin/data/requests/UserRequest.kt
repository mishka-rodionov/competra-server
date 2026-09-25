package com.rodionov.remote.request.user

import com.competra.domain.user.Gender
import com.google.gson.annotations.SerializedName
import java.util.Date

data class UserRequest(
//    val id: String,
    @SerializedName("first_name")
    val firstName: String,
    @SerializedName("last_name")
    val lastName: String,
//    val middleName: String?, // отчество
    @SerializedName("birth_date")
    val birthDate: String,
//    val gender: Gender,
//    val photo: String,
//    val phoneNumber: String?,
    @SerializedName("email")
    val email: String,
//    val qualification: List<Qualification>
    @SerializedName("privacy_accepted")
    val privacyAccepted: Boolean = false,
    /** Пол пользователя. Null — старые клиенты, которые не присылают поле. */
    @SerializedName("gender")
    val gender: Gender? = null,
)
