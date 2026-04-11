package com.sportenth

import com.sportenth.data.database.entity.VerificationCodes
import com.sportenth.domain.user.Gender
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.Date

@Serializable
data class UserEntity(
    val id: String,
    val firstName: String,
    val lastName: String,
    val middleName: String?, // отчество
    val birthDate: String,
//    val gender: Gender,
    val photo: String,
    val phoneNumber: String?,
    val email: String,
)

class UserService(database: Database) {
    object Users : Table() {
        val id = varchar("id", length = 200)
        val email = varchar("email", length = 50)
        val firstName = varchar("first_name", length = 100)
        val lastName = varchar("last_name", length = 100)
        val middleName = varchar("middle_name", length = 100)
        val birthDate = varchar("birth_date", length = 20)
//        val gender: Gender,
        val photo = varchar("photo", length = 300)
        val phoneNumber = varchar("phone_number", length = 20)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(Users, VerificationCodes)
        }
    }

    suspend fun create(user: UserEntity): String = dbQuery {
        Users.insert {
            it[id] = user.id
            it[firstName] = user.firstName
            it[lastName] = user.lastName
            it[middleName] = user.middleName ?: ""
            it[birthDate] = user.birthDate
            it[photo] = user.photo
            it[phoneNumber] = user.phoneNumber ?: ""
            it[email] = user.email
        }[Users.id]
    }

    suspend fun read(id: String): UserEntity? {
        return dbQuery {
            Users.selectAll()
                .where { Users.id eq id }
                .map { UserEntity(
                    email = it[Users.email],
                    id = it[Users.id],
                    firstName = it[Users.firstName],
                    lastName = it[Users.lastName],
                    middleName = it[Users.middleName],
                    birthDate = it[Users.birthDate],
//                    gender = it[Users.gender],
                    photo = it[Users.photo],
                    phoneNumber = it[Users.phoneNumber],
                ) }
                .singleOrNull()
        }
    }

    suspend fun update(id: String, user: UserEntity) {
        dbQuery {
            Users.update({ Users.id eq id }) {
                it[firstName] = user.firstName
                it[lastName] = user.lastName
                it[middleName] = user.middleName ?: ""
                it[birthDate] = user.birthDate
                it[photo] = user.photo
                it[phoneNumber] = user.phoneNumber ?: ""
                it[email] = user.email
            }
        }
    }

    suspend fun delete(id: String) {
        dbQuery {
            Users.deleteWhere { Users.id.eq(id) }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) {
            addLogger(Slf4jSqlDebugLogger)
            block()
        }
}

