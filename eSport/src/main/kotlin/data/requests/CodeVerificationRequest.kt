package com.competra.data.requests

import kotlinx.serialization.Serializable

@Serializable
data class CodeVerificationRequest(val email: String, val code: String)
