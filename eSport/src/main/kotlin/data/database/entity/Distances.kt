package com.competra.data.database.entity

import org.jetbrains.exposed.sql.Table

object Distances : Table("distances") {
    val id = long("id").autoIncrement()
    val competitionId = varchar("competition_id", 36)
        .references(Competitions.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val name = varchar("name", 200).nullable()
    val lengthMeters = integer("length_meters")
    val climbMeters = integer("climb_meters")
    val controlsCount = integer("controls_count")
    val description = varchar("description", 1000).nullable()
    val controlPoints = text("control_points").nullable()
    val finishControlPoint = integer("finish_control_point").nullable()
    val startControlPoint = integer("start_control_point").nullable()
    val mapUrl = varchar("map_url", 500).nullable()
    val mapTopLeftLat = double("map_top_left_lat").nullable()
    val mapTopLeftLng = double("map_top_left_lng").nullable()
    // Верхний правый угол. Если заполнен — top_left/bottom_right трактуются как точные углы
    // растра (привязка по трём точкам, карта может быть повёрнута); если пуст — как bbox «север вверх».
    val mapTopRightLat = double("map_top_right_lat").nullable()
    val mapTopRightLng = double("map_top_right_lng").nullable()
    val mapBottomRightLat = double("map_bottom_right_lat").nullable()
    val mapBottomRightLng = double("map_bottom_right_lng").nullable()
    val updatedAt = long("updated_at").default(0L)

    override val primaryKey = PrimaryKey(id)
}
