package com.sportenth.data.database

import com.rodionov.remote.request.user.UserRequest
import com.sportenth.UserEntity
import com.sportenth.UserService
import com.sportenth.data.database.entity.Competitions
import com.sportenth.data.database.entity.Distances
import com.sportenth.data.database.entity.OrienteeringCompetitions
import com.sportenth.data.database.entity.OrienteeringParticipants
import com.sportenth.data.database.entity.OrienteeringResults
import com.sportenth.data.database.entity.ParticipantGroups
import com.sportenth.data.database.entity.RefreshTokens
import com.sportenth.data.database.entity.SplitTimes
import com.sportenth.data.database.entity.VerificationCodes
import com.sportenth.data.requests.CodeVerificationRequest
import com.sportenth.data.requests.EmailRequest
import com.sportenth.data.requests.RefreshRequest
import com.sportenth.data.response.AuthResponse
import com.sportenth.data.response.TokenResponse
import com.sportenth.data.response.base.BaseError
import com.sportenth.data.response.base.CommonModel
import com.sportenth.data.response.user.UserResponse
import com.sportenth.data.services.smtp.sendVerificationCode
import com.sportenth.data.services.smtp.tokens.generateAccessToken
import com.sportenth.data.services.smtp.tokens.generateRefreshToken
import com.sportenth.data.services.smtp.tokens.jwtAudience
import com.sportenth.data.services.smtp.tokens.jwtIssuer
import com.sportenth.data.services.smtp.tokens.jwtSecret
import com.sportenth.domain.user.Gender
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.days

val tempUsers = mutableListOf<UserRequest>()

fun Application.configureDatabases() {
//    val database = Database.connect(
//        url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
//        user = "root",
//        driver = "org.h2.Driver",
//        password = "",
//    )

    val database = Database.connect(
        url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/postgres",
        driver = "org.postgresql.Driver",
        user = System.getenv("DB_USER") ?: "rodionov",
        password = System.getenv("DB_PASSWORD") ?: "123456789"
    )
    val userService = UserService(database)
    transaction(database) {
        SchemaUtils.create(
            Competitions,
            OrienteeringCompetitions,
            Distances,
            ParticipantGroups,
            OrienteeringParticipants,
            OrienteeringResults,
            SplitTimes,
            RefreshTokens
        )
        // Добавляем колонки, которых может не быть в уже существующей таблице
        exec("ALTER TABLE participant_groups ALTER COLUMN distance_id TYPE BIGINT USING distance_id::BIGINT")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS status VARCHAR(100) NOT NULL DEFAULT 'CREATED'")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS results_status VARCHAR(100) NOT NULL DEFAULT 'NOT_PUBLISHED'")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS registration_start BIGINT")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS registration_end BIGINT")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS max_participants INTEGER")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS fee_amount DOUBLE PRECISION")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS fee_currency VARCHAR(10)")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS image_url VARCHAR(500)")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS regulation_url VARCHAR(500)")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS map_url VARCHAR(500)")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(50)")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS contact_email VARCHAR(200)")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS website VARCHAR(500)")
    }
    routing {
        // Create user
        post("/users") {
            val user = call.receive<UserEntity>()
            val id = userService.create(user)
            call.respond(HttpStatusCode.Created, id)
        }

        // Read user
        get("/users/{id}") {
            val id = call.parameters["id"] ?: throw IllegalArgumentException("Invalid ID")
            val user = userService.read(id)
            if (user != null) {
                call.respond(HttpStatusCode.OK, user)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        // Update user
        put("/users/{id}") {
            val id = call.parameters["id"] ?: throw IllegalArgumentException("Invalid ID")
            val user = call.receive<UserEntity>()
            userService.update(id, user)
            call.respond(HttpStatusCode.OK)
        }

        // Delete user
        delete("/users/{id}") {
            val id = call.parameters["id"] ?: throw IllegalArgumentException("Invalid ID")
            userService.delete(id)
            call.respond(HttpStatusCode.OK)
        }

        post("/user/login") {
            val request = call.receive<EmailRequest>()
            createAndSendVerificationCode(request.email)
            call.respond(CommonModel<Any>().also { model -> model.status = 1 })
        }

        post("/user/register") {
            val userRequest = call.receive<UserRequest>()

            val emailAlreadyUsed = transaction {
                UserService.Users.selectAll().where { UserService.Users.email eq userRequest.email }.singleOrNull() != null
            }
            if (emailAlreadyUsed) {
                call.respond(
                    CommonModel<Any>().also { model ->
                        model.status = 0
                        model.errors = listOf(BaseError(code = 1001, message = "Данная электронная почта уже используется. Введите новую."))
                    }
                )
                return@post
            }

            createAndSendVerificationCode(userRequest.email)
            tempUsers.add(userRequest)
            call.respond(CommonModel<Any>().also { model -> model.status = 1 })
        }

        // 2. Проверка кода
        post("/user/verify_code") {
            val request = call.receive<CodeVerificationRequest>()
            val storedCode = transaction {
                VerificationCodes.selectAll().where { VerificationCodes.email eq request.email }
                    .singleOrNull()?.get(VerificationCodes.code)
            }

            if (storedCode == null || storedCode != request.code) {
                call.respond(mapOf("error" to "Invalid code"))
                return@post
            }

            val tempUser = tempUsers.firstOrNull { it.email == request.email }

            val newUserId = UUID.randomUUID().toString()

            val userId = transaction {
                val existing =
                    UserService.Users.selectAll().where { UserService.Users.email eq request.email }.singleOrNull()
                if (existing != null) async { existing[UserService.Users.id] }
                else {
                    async {
                        userService.create(
                            UserEntity(
                                id = newUserId,
                                firstName = tempUser?.firstName ?: "",
                                lastName = tempUser?.lastName ?: "",
                                middleName = "",
                                birthDate = tempUser?.birthDate ?: "",
                                photo = "",
                                phoneNumber = "",
                                email = request.email
                            )
                        )
                    }
                }
            }.await()

            // Удаляем использованный код
            transaction { VerificationCodes.deleteWhere { VerificationCodes.email eq request.email } }

            val accessToken = generateAccessToken(userId)
            val refreshToken = generateRefreshToken()

            // Store refresh token in DB
            transaction {
                RefreshTokens.insert {
                    it[token] = refreshToken
                    it[RefreshTokens.userId] = userId
                    it[expiresAt] = System.currentTimeMillis() + 30.days.inWholeMilliseconds
                }
            }

            tempUsers.removeIf { it.email == request.email }

            val user = userService.read(userId)

            val response: CommonModel<AuthResponse> = CommonModel<AuthResponse>().also { model -> model.status = if (user != null) 1 else 0 }
            if (user != null) {
                response.result = AuthResponse(
                    user = UserResponse(
                        id = user.id,
                        firstName = user.firstName,
                        lastName = user.lastName,
                        middleName = user.middleName,
                        birthDate = user.birthDate,
                        gender = Gender.MALE,
                        photo = user.photo,
                        phoneNumber = user.phoneNumber,
                        email = user.email,
                        qualification = emptyList()
                    ),
                    token = TokenResponse(accessToken, refreshToken)
                )
            } else {
                response.errors = listOf(BaseError(code = 500, message = "Internal Server Error: User is Null"))
            }


            call.respond(response)
        }

        post("/refresh_token") {
            val request = call.receive<RefreshRequest>()

            val tokenRow = transaction {
                RefreshTokens.selectAll()
                    .where { RefreshTokens.token eq request.refreshToken }
                    .singleOrNull()
            }

            if (tokenRow == null) {
                call.respond(
                    CommonModel<Any>().also { model ->
                        model.status = 0
                        model.errors = listOf(BaseError(401, "Invalid refresh token"))
                    }
                )
                return@post
            }

            if (tokenRow[RefreshTokens.expiresAt] < System.currentTimeMillis()) {
                transaction { RefreshTokens.deleteWhere { RefreshTokens.token eq request.refreshToken } }
                call.respond(
                    CommonModel<Any>().also { model ->
                        model.status = 0
                        model.errors = listOf(BaseError(401, "Refresh token expired"))
                    }
                )
                return@post
            }

            val userId = tokenRow[RefreshTokens.userId]
            val user = userService.read(userId)

            // Rotate refresh token
            transaction { RefreshTokens.deleteWhere { RefreshTokens.token eq request.refreshToken } }
            val newAccessToken = generateAccessToken(userId)
            val newRefreshToken = generateRefreshToken()
            transaction {
                RefreshTokens.insert {
                    it[token] = newRefreshToken
                    it[RefreshTokens.userId] = userId
                    it[expiresAt] = System.currentTimeMillis() + 30.days.inWholeMilliseconds
                }
            }

            val response = CommonModel<AuthResponse>().also { model -> model.status = if (user != null) 1 else 0 }
            if (user != null) {
                response.result = AuthResponse(
                    user = UserResponse(
                        id = user.id,
                        firstName = user.firstName,
                        lastName = user.lastName,
                        middleName = user.middleName,
                        birthDate = user.birthDate,
                        gender = Gender.MALE,
                        photo = user.photo,
                        phoneNumber = user.phoneNumber,
                        email = user.email,
                        qualification = emptyList()
                    ),
                    token = TokenResponse(newAccessToken, newRefreshToken)
                )
            } else {
                response.errors = listOf(BaseError(code = 500, message = "Internal Server Error: User is Null"))
            }
            call.respond(response)
        }
    }
}

private fun createAndSendVerificationCode(email: String) {
    val code = (100000..999999).random().toString()

    transaction {
        VerificationCodes.upsert(
            VerificationCodes.email,
            body = {
                it[VerificationCodes.email] = email
                it[VerificationCodes.code] = code
                it[VerificationCodes.createdAt] = LocalDateTime.now()
            },
            /*where = {
                        VerificationCodes.email eq request.email
                    }*/
        )
    }

    sendVerificationCode(email, code)
//            call.respond(mapOf("message" to "Verification code sent"))
}
