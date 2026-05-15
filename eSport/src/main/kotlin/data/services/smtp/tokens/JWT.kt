package com.competra.data.services.smtp.tokens

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

val jwtIssuer: String = System.getenv("JWT_ISSUER") ?: "ktor_server"
val jwtAudience: String = System.getenv("JWT_AUDIENCE") ?: "ktor_audience"
val jwtSecret: String = System.getenv("JWT_SECRET") ?: "super_secret_jwt_key"

fun generateAccessToken(userId: String): String =
    JWT.create()
        .withIssuer(jwtIssuer)
        .withAudience(jwtAudience)
        .withClaim("userId", userId)
        .withExpiresAt(Date(System.currentTimeMillis() + 15.minutes.inWholeMilliseconds))
        .sign(Algorithm.HMAC256(jwtSecret))

fun generateRefreshToken(): String = UUID.randomUUID().toString()