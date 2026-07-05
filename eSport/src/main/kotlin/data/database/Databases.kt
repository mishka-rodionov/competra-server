package com.competra.data.database

import com.rodionov.remote.request.user.UserRequest
import com.competra.UserEntity
import com.competra.UserService
import com.competra.data.requests.UserProfileRequest
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import com.competra.data.database.entity.Competitions
import com.competra.data.database.entity.DeviceTokens
import com.competra.data.database.entity.Distances
import com.competra.data.database.entity.OrienteeringCompetitions
import com.competra.data.database.entity.OrienteeringParticipants
import com.competra.data.database.entity.OrienteeringResults
import com.competra.data.database.entity.ParticipantGroups
import com.competra.data.database.entity.RefreshTokens
import com.competra.data.database.entity.SplitTimes
import com.competra.data.database.entity.VerificationCodes
import com.competra.data.database.entity.diary.BikeDetails
import com.competra.data.database.entity.diary.RunDetails
import com.competra.data.database.entity.diary.SkiDetails
import com.competra.data.database.entity.diary.Workouts
import com.competra.data.requests.CodeVerificationRequest
import com.competra.data.requests.EmailRequest
import com.competra.data.requests.RefreshRequest
import com.competra.data.response.AuthResponse
import com.competra.data.response.TokenResponse
import com.competra.data.response.base.BaseError
import com.competra.data.response.base.CommonModel
import com.competra.data.response.user.UserResponse
import com.competra.data.services.smtp.sendVerificationCode
import com.competra.data.services.smtp.tokens.generateAccessToken
import com.competra.data.services.smtp.tokens.generateRefreshToken
import com.competra.data.services.smtp.tokens.jwtAudience
import com.competra.data.services.smtp.tokens.jwtIssuer
import com.competra.data.services.smtp.tokens.jwtSecret
import com.competra.domain.user.Gender
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
            RefreshTokens,
            DeviceTokens,
            Workouts,
            RunDetails,
            BikeDetails,
            SkiDetails
        )
        // Добавляем колонки, которых может не быть в уже существующей таблице
        exec("ALTER TABLE workouts ADD COLUMN IF NOT EXISTS track TEXT")
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
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS results_url VARCHAR(1000)")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(50)")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS contact_email VARCHAR(200)")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS website VARCHAR(500)")
        exec("ALTER TABLE distances ADD COLUMN IF NOT EXISTS control_points TEXT")
        exec("ALTER TABLE distances ADD COLUMN IF NOT EXISTS finish_control_point INTEGER")
        // updated_at — серверная сторона serverUpdatedAt в Android-клиенте.
        // Колонка объявлена в Exposed Table-объектах (default(0L)), но в существующих
        // PostgreSQL-таблицах её нет — нужны идемпотентные ALTER'ы для каждой таблицы.
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS updated_at BIGINT NOT NULL DEFAULT 0")
        exec("ALTER TABLE orienteering_competitions ADD COLUMN IF NOT EXISTS updated_at BIGINT NOT NULL DEFAULT 0")
        exec("ALTER TABLE distances ADD COLUMN IF NOT EXISTS updated_at BIGINT NOT NULL DEFAULT 0")
        exec("ALTER TABLE participant_groups ADD COLUMN IF NOT EXISTS updated_at BIGINT NOT NULL DEFAULT 0")
        exec("ALTER TABLE orienteering_participants ADD COLUMN IF NOT EXISTS updated_at BIGINT NOT NULL DEFAULT 0")
        exec("ALTER TABLE orienteering_results ADD COLUMN IF NOT EXISTS updated_at BIGINT NOT NULL DEFAULT 0")
        exec("ALTER TABLE split_times ADD COLUMN IF NOT EXISTS updated_at BIGINT NOT NULL DEFAULT 0")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS start_notification_sent BOOLEAN NOT NULL DEFAULT FALSE")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS time_zone_id VARCHAR(64) NOT NULL DEFAULT 'UTC'")
        // is_test — тестовые соревнования исключаются из публичной ленты, видны только владельцу.
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS is_test BOOLEAN NOT NULL DEFAULT FALSE")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS cover_crop_x DOUBLE PRECISION")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS cover_crop_y DOUBLE PRECISION")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS cover_crop_width DOUBLE PRECISION")
        exec("ALTER TABLE competitions ADD COLUMN IF NOT EXISTS cover_crop_height DOUBLE PRECISION")
        exec("ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_crop_x DOUBLE PRECISION")
        exec("ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_crop_y DOUBLE PRECISION")
        exec("ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_crop_width DOUBLE PRECISION")
        exec("ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_crop_height DOUBLE PRECISION")

        // ── Миграция идентичности соревнований на единый клиентский UUID ──────────────
        // competitions.id: BIGINT → VARCHAR(36) (UUID). orienteering_competitions и все
        // дочерние таблицы переводят competition_id на тот же UUID. Прежний BIGINT
        // сохраняется в competitions.legacy_id для перехода клиентов. Восстанавливаются
        // строки orienteering_competitions для осиротевших соревнований (баг VIP-Android).
        //
        // Блок идемпотентен: выполняется один раз, пока competitions.id ещё BIGINT.
        // Внутренние SQL планируются PL/pgSQL лениво, поэтому ссылки на удаляемые позже
        // колонки (orienteering_competitions.user_id/competition_id) безопасны при early-return.
        exec(
            """
            DO ${'$'}${'$'}
            BEGIN
                IF NOT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_name = 'competitions' AND column_name = 'id' AND data_type = 'bigint'
                ) THEN
                    RETURN;
                END IF;

                -- 1) Новые колонки ядра + генерация UUID (без зависимости от расширений).
                ALTER TABLE competitions ADD COLUMN IF NOT EXISTS id_uuid VARCHAR(36);
                ALTER TABLE competitions ADD COLUMN IF NOT EXISTS legacy_id BIGINT;
                ALTER TABLE competitions ADD COLUMN IF NOT EXISTS owner_id VARCHAR(200);

                UPDATE competitions SET
                    legacy_id = id,
                    id_uuid = (
                        SELECT substr(m,1,8)||'-'||substr(m,9,4)||'-'||substr(m,13,4)||'-'||substr(m,17,4)||'-'||substr(m,21,12)
                        FROM (SELECT md5(random()::text || clock_timestamp()::text || competitions.id::text) AS m) s
                    )
                WHERE id_uuid IS NULL;

                -- Владелец из существующего orienteering_competitions.user_id (колонка ещё есть).
                UPDATE competitions c SET owner_id = o.user_id
                    FROM orienteering_competitions o
                    WHERE o.competition_id = c.id AND c.owner_id IS NULL;

                -- 2) Перемапить competition_id дочерних таблиц на новый UUID (через временную колонку).
                ALTER TABLE distances ADD COLUMN IF NOT EXISTS competition_uuid VARCHAR(36);
                UPDATE distances ch SET competition_uuid = c.id_uuid FROM competitions c WHERE ch.competition_id = c.id;
                DELETE FROM distances WHERE competition_uuid IS NULL;

                ALTER TABLE participant_groups ADD COLUMN IF NOT EXISTS competition_uuid VARCHAR(36);
                UPDATE participant_groups ch SET competition_uuid = c.id_uuid FROM competitions c WHERE ch.competition_id = c.id;
                DELETE FROM participant_groups WHERE competition_uuid IS NULL;

                ALTER TABLE orienteering_participants ADD COLUMN IF NOT EXISTS competition_uuid VARCHAR(36);
                UPDATE orienteering_participants ch SET competition_uuid = c.id_uuid FROM competitions c WHERE ch.competition_id = c.id;
                DELETE FROM orienteering_participants WHERE competition_uuid IS NULL;

                ALTER TABLE orienteering_results ADD COLUMN IF NOT EXISTS competition_uuid VARCHAR(36);
                UPDATE orienteering_results ch SET competition_uuid = c.id_uuid FROM competitions c WHERE ch.competition_id = c.id;
                DELETE FROM orienteering_results WHERE competition_uuid IS NULL;

                -- orienteering_competitions: новый id = uuid связанного соревнования.
                ALTER TABLE orienteering_competitions ADD COLUMN IF NOT EXISTS id_uuid VARCHAR(36);
                UPDATE orienteering_competitions o SET id_uuid = c.id_uuid FROM competitions c WHERE o.competition_id = c.id;
                DELETE FROM orienteering_competitions WHERE id_uuid IS NULL;

                -- 3) Свап первичного ключа competitions: BIGINT id → VARCHAR id_uuid.
                ALTER TABLE competitions DROP CONSTRAINT IF EXISTS competitions_pkey;
                ALTER TABLE competitions DROP COLUMN id;
                ALTER TABLE competitions RENAME COLUMN id_uuid TO id;
                ALTER TABLE competitions ALTER COLUMN id SET NOT NULL;
                ALTER TABLE competitions ADD PRIMARY KEY (id);

                -- 4) Свап PK orienteering_competitions + удаление устаревших колонок.
                ALTER TABLE orienteering_competitions DROP CONSTRAINT IF EXISTS orienteering_competitions_pkey;
                ALTER TABLE orienteering_competitions DROP COLUMN id;
                ALTER TABLE orienteering_competitions RENAME COLUMN id_uuid TO id;
                ALTER TABLE orienteering_competitions ALTER COLUMN id SET NOT NULL;
                ALTER TABLE orienteering_competitions ADD PRIMARY KEY (id);
                ALTER TABLE orienteering_competitions DROP COLUMN IF EXISTS competition_id;
                ALTER TABLE orienteering_competitions DROP COLUMN IF EXISTS user_id;

                -- 5) Свап competition_id дочерних таблиц.
                ALTER TABLE distances DROP COLUMN competition_id;
                ALTER TABLE distances RENAME COLUMN competition_uuid TO competition_id;
                ALTER TABLE distances ALTER COLUMN competition_id SET NOT NULL;

                ALTER TABLE participant_groups DROP COLUMN competition_id;
                ALTER TABLE participant_groups RENAME COLUMN competition_uuid TO competition_id;
                ALTER TABLE participant_groups ALTER COLUMN competition_id SET NOT NULL;

                ALTER TABLE orienteering_participants DROP COLUMN competition_id;
                ALTER TABLE orienteering_participants RENAME COLUMN competition_uuid TO competition_id;
                ALTER TABLE orienteering_participants ALTER COLUMN competition_id SET NOT NULL;

                ALTER TABLE orienteering_results DROP COLUMN competition_id;
                ALTER TABLE orienteering_results RENAME COLUMN competition_uuid TO competition_id;
                ALTER TABLE orienteering_results ALTER COLUMN competition_id SET NOT NULL;

                -- 6) Восстановить orienteering_competitions для осиротевших соревнований (баг VIP-Android).
                INSERT INTO orienteering_competitions (id, direction, punching_system, start_time_mode, start_interval_seconds, updated_at)
                SELECT c.id, 'FORWARD', 'SPORTIDENT', 'USER_SET', NULL, 0
                FROM competitions c
                WHERE NOT EXISTS (SELECT 1 FROM orienteering_competitions o WHERE o.id = c.id);

                -- 7) Внешние ключи: 1:1 ядро↔расширение и дочерние, все ON DELETE CASCADE.
                ALTER TABLE orienteering_competitions
                    ADD CONSTRAINT fk_orient_competition FOREIGN KEY (id) REFERENCES competitions(id) ON DELETE CASCADE;
                ALTER TABLE distances
                    ADD CONSTRAINT fk_distances_competition FOREIGN KEY (competition_id) REFERENCES competitions(id) ON DELETE CASCADE;
                ALTER TABLE participant_groups
                    ADD CONSTRAINT fk_groups_competition FOREIGN KEY (competition_id) REFERENCES competitions(id) ON DELETE CASCADE;
                ALTER TABLE orienteering_participants
                    ADD CONSTRAINT fk_participants_competition FOREIGN KEY (competition_id) REFERENCES competitions(id) ON DELETE CASCADE;
                ALTER TABLE orienteering_results
                    ADD CONSTRAINT fk_results_competition FOREIGN KEY (competition_id) REFERENCES competitions(id) ON DELETE CASCADE;
            END
            ${'$'}${'$'};
            """.trimIndent()
        )
    }
    routing {
        route("/api") {
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
                        avatarUrl = user.photo,
                        avatarCropX = user.avatarCropX,
                        avatarCropY = user.avatarCropY,
                        avatarCropWidth = user.avatarCropWidth,
                        avatarCropHeight = user.avatarCropHeight,
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

        authenticate("auth-jwt") {
            get("/user/profile") {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
                val user = userService.read(userId)
                if (user != null) {
                    call.respond(CommonModel<UserResponse>().also {
                        it.status = 1
                        it.result = UserResponse(
                            id = user.id,
                            firstName = user.firstName,
                            lastName = user.lastName,
                            middleName = user.middleName,
                            birthDate = user.birthDate,
                            gender = Gender.MALE,
                            avatarUrl = user.photo,
                            avatarCropX = user.avatarCropX,
                            avatarCropY = user.avatarCropY,
                            avatarCropWidth = user.avatarCropWidth,
                            avatarCropHeight = user.avatarCropHeight,
                            phoneNumber = user.phoneNumber,
                            email = user.email,
                            qualification = emptyList()
                        )
                    })
                } else {
                    call.respond(CommonModel<Any>().also {
                        it.status = 0
                        it.errors = listOf(BaseError(404, "User not found"))
                    })
                }
            }

            patch("/user/profile") {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
                val request = call.receive<UserProfileRequest>()
                val current = userService.read(userId)
                if (current == null) {
                    call.respond(CommonModel<Any>().also {
                        it.status = 0
                        it.errors = listOf(BaseError(404, "User not found"))
                    })
                    return@patch
                }
                userService.update(userId, UserEntity(
                    id = userId,
                    firstName = request.firstName ?: current.firstName,
                    lastName = request.lastName ?: current.lastName,
                    middleName = request.middleName ?: current.middleName,
                    birthDate = request.birthDate ?: current.birthDate,
                    photo = request.avatarUrl ?: current.photo,
                    phoneNumber = request.phoneNumber ?: current.phoneNumber,
                    email = current.email,
                    avatarCropX = request.avatarCropX ?: current.avatarCropX,
                    avatarCropY = request.avatarCropY ?: current.avatarCropY,
                    avatarCropWidth = request.avatarCropWidth ?: current.avatarCropWidth,
                    avatarCropHeight = request.avatarCropHeight ?: current.avatarCropHeight
                ))
                val updated = userService.read(userId)!!
                call.respond(CommonModel<UserResponse>().also {
                    it.status = 1
                    it.result = UserResponse(
                        id = updated.id,
                        firstName = updated.firstName,
                        lastName = updated.lastName,
                        middleName = updated.middleName,
                        birthDate = updated.birthDate,
                        gender = Gender.MALE,
                        avatarUrl = updated.photo,
                        avatarCropX = updated.avatarCropX,
                        avatarCropY = updated.avatarCropY,
                        avatarCropWidth = updated.avatarCropWidth,
                        avatarCropHeight = updated.avatarCropHeight,
                        phoneNumber = updated.phoneNumber,
                        email = updated.email,
                        qualification = emptyList()
                    )
                })
            }
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
                        avatarUrl = user.photo,
                        avatarCropX = user.avatarCropX,
                        avatarCropY = user.avatarCropY,
                        avatarCropWidth = user.avatarCropWidth,
                        avatarCropHeight = user.avatarCropHeight,
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
