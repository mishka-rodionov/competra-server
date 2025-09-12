package com.sportenth.data.database

import com.rodionov.remote.request.user.UserRequest
import com.sportenth.UserEntity
import com.sportenth.UserService
import com.sportenth.data.database.entity.VerificationCodes
import com.sportenth.data.requests.CodeVerificationRequest
import com.sportenth.data.requests.EmailRequest
import com.sportenth.data.response.AuthResponse
import com.sportenth.data.response.TokenResponse
import com.sportenth.data.response.base.BaseError
import com.sportenth.data.response.base.CommonModel
import com.sportenth.data.response.user.UserResponse
import com.sportenth.data.services.smtp.sendVerificationCode
import com.sportenth.data.services.smtp.tokens.generateAccessToken
import com.sportenth.data.services.smtp.tokens.generateRefreshToken
import com.sportenth.domain.user.Gender
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

val tempUsers = mutableListOf<UserRequest>()

fun Application.configureDatabases() {
//    val database = Database.connect(
//        url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
//        user = "root",
//        driver = "org.h2.Driver",
//        password = "",
//    )

    val database = Database.connect(
        url = "jdbc:postgresql://localhost:5432/sportenth",
        driver = "org.postgresql.Driver",
        user = "postgres",
        password = "123456789"
    )
    val userService = UserService(database)
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
