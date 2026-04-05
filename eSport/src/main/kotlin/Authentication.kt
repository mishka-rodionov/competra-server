package com.sportenth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sportenth.data.services.smtp.tokens.jwtAudience
import com.sportenth.data.services.smtp.tokens.jwtIssuer
import com.sportenth.data.services.smtp.tokens.jwtSecret
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt

fun Application.configureAuthentication() {
    install(Authentication) {
        jwt("auth-jwt") {
            verifier(
                JWT
                    .require(Algorithm.HMAC256(jwtSecret))
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.getClaim("userId").asString() != null) JWTPrincipal(credential.payload) else null
            }
        }
    }
}