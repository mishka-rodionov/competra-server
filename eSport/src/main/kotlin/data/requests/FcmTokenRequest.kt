package com.competra.data.requests

import kotlinx.serialization.Serializable

@Serializable
data class FcmTokenRequest(
    val token: String,
    val platform: String = "android",
    val appVersion: String? = null,
)
