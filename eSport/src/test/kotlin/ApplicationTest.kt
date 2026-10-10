package com.competra

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke-тест HTTP-слоя без инфраструктуры. Полный [module] здесь не поднимается: он требует
 * переменных окружения (JWT_SECRET, DB_PASSWORD, ...) и PostgreSQL — схема создаётся
 * Postgres-специфичным SQL.
 */
class ApplicationTest {

    @Test
    fun healthRespondsUpWithoutDatabase() = testApplication {
        application {
            configureSerialization()
            routing { healthRoutes() }
        }
        client.get("/health").apply {
            assertEquals(HttpStatusCode.OK, status)
            assertTrue(bodyAsText().contains("UP"))
        }
    }

}
