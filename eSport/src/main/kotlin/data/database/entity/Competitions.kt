package com.competra.data.database.entity

import org.jetbrains.exposed.sql.Table

object Competitions : Table("competitions") {
    /** Глобально-уникальный клиентский UUID — единый идентификатор соревнования на всех платформах. */
    val id = varchar("id", 36)
    /** Прежний серверный BIGINT-идентификатор. Хранится на время перехода клиентов на UUID. */
    val legacyId = long("legacy_id").nullable()
    /** Владелец/создатель соревнования (userId из JWT). Спорт-независим, лежит в ядре. */
    val ownerId = varchar("owner_id", 200).nullable()
    val title = varchar("title", 500)
    val startDate = long("start_date")
    val endDate = long("end_date").nullable()
    val kindOfSport = varchar("kind_of_sport", 100)
    val description = varchar("description", 1000).nullable()
    val address = varchar("address", 500).nullable()
    val mainOrganizerId = varchar("main_organizer_id", 200).nullable()
    val latitude = double("latitude").nullable()
    val longitude = double("longitude").nullable()
    val status = varchar("status", 100).default("CREATED")
    val registrationStart = long("registration_start").nullable()
    val registrationEnd = long("registration_end").nullable()
    val maxParticipants = integer("max_participants").nullable()
    val feeAmount = double("fee_amount").nullable()
    val feeCurrency = varchar("fee_currency", 10).nullable()
    val imageUrl = varchar("image_url", 500).nullable()
    val coverCropX = double("cover_crop_x").nullable()
    val coverCropY = double("cover_crop_y").nullable()
    val coverCropWidth = double("cover_crop_width").nullable()
    val coverCropHeight = double("cover_crop_height").nullable()
    val regulationUrl = varchar("regulation_url", 500).nullable()
    val mapUrl = varchar("map_url", 500).nullable()
    val resultsUrl = varchar("results_url", 1000).nullable()
    val contactPhone = varchar("contact_phone", 50).nullable()
    val contactEmail = varchar("contact_email", 200).nullable()
    val website = varchar("website", 500).nullable()
    val resultsStatus = varchar("results_status", 100).default("NOT_PUBLISHED")
    /** Тестовое соревнование: исключается из публичной ленты, видно только владельцу. */
    val isTest = bool("is_test").default(false)
    val updatedAt = long("updated_at").default(0L)
    val startNotificationSent = bool("start_notification_sent").default(false)
    val timeZoneId = varchar("time_zone_id", 64).default("UTC")
    /** Опциональный владелец-клуб. Удаление клуба не удаляет соревнование (ON DELETE SET NULL). */
    val organizingClubId = varchar("organizing_club_id", 36).nullable()

    override val primaryKey = PrimaryKey(id)
}
