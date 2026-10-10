package com.competra.data.database.entity

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** Организатор/судья/секретарь и т.д. соревнования. Many-to-many между пользователями и соревнованиями с ролью. */
object CompetitionOrganizers : Table("competition_organizers") {
    val id = long("id").autoIncrement()
    val competitionId = varchar("competition_id", 36)
        .references(Competitions.id, onDelete = ReferenceOption.CASCADE)
    /** Если организатор зарегистрирован в системе. */
    val userId = varchar("user_id", 200).nullable()
    /** Если организатор не зарегистрирован — просто ФИО. */
    val name = varchar("name", 300).nullable()
    /** MAIN | JUDGE | SECRETARY | COURSE_SETTER | OTHER */
    val role = varchar("role", 20)
    val contactEmail = varchar("contact_email", 200).nullable()
    val contactPhone = varchar("contact_phone", 50).nullable()
    val updatedAt = long("updated_at").default(0L)

    override val primaryKey = PrimaryKey(id)
}
