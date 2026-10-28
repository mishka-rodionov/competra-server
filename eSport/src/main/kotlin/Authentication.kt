package com.competra

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.competra.data.services.smtp.tokens.jwtAudience
import com.competra.data.services.smtp.tokens.jwtIssuer
import com.competra.data.services.smtp.tokens.jwtSecret
import com.auth0.jwt.JWTVerifier
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt

private val jwtVerifier: JWTVerifier = JWT
    .require(Algorithm.HMAC256(jwtSecret))
    .withAudience(jwtAudience)
    .withIssuer(jwtIssuer)
    .build()

fun Application.configureAuthentication() {
    install(Authentication) {
        jwt("auth-jwt") {
            verifier(jwtVerifier)
            validate { credential ->
                if (credential.payload.getClaim("userId").asString() != null) JWTPrincipal(credential.payload) else null
            }
        }
    }
}

/**
 * userId из Bearer-токена для публичных ручек, ответ которых зависит от зрителя. В отличие от
 * `authenticate(optional = true)` невалидный или просроченный токен не даёт 401, а просто
 * означает анонима — иначе клиент с протухшей сессией перестал бы видеть публичные данные.
 */
fun ApplicationCall.optionalUserId(): String? {
    val token = request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring("Bearer ".length)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return try {
        jwtVerifier.verify(token).getClaim("userId").asString()
    } catch (e: Exception) {
        null
    }
}
