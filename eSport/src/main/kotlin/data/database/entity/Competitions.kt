package com.sportenth.data.database.entity

import org.jetbrains.exposed.sql.Table

object Competitions : Table("competitions") {
    val id = long("id").autoIncrement()
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
    val regulationUrl = varchar("regulation_url", 500).nullable()
    val mapUrl = varchar("map_url", 500).nullable()
    val contactPhone = varchar("contact_phone", 50).nullable()
    val contactEmail = varchar("contact_email", 200).nullable()
    val website = varchar("website", 500).nullable()
    val resultsStatus = varchar("results_status", 100).default("NOT_PUBLISHED")

    override val primaryKey = PrimaryKey(id)
}
