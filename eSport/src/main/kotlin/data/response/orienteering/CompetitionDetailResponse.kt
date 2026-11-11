package com.competra.data.response.orienteering

data class ParticipantGroupDetailResponse(
    val groupId: Long,
    val title: String,
    /** "M" / "F" / null — используется для сортировки групп (мужские → женские → остальные). */
    val gender: String? = null,
    val maxParticipants: Int?,
    val registeredCount: Int,
    val distanceId: Long? = null,
    val distanceName: String? = null,
    val distanceLengthMeters: Int? = null,
    val distanceClimbMeters: Int? = null,
    val distanceControlsCount: Int? = null,
    val distanceDescription: String? = null,
    /** Минимум КП дистанции (BY_CHOICE + MIN_CONTROLS); null — взять все КП. */
    val distanceMinControlsCount: Int? = null,
    /**
     * Эффективное направление группы: своё у её дистанции, иначе соревнования (группа без
     * дистанции — соревнования). FORWARD / BY_CHOICE / MARKING.
     */
    val direction: String = "FORWARD",
    /** Эффективный режим «по выбору» группы (SCORE / MIN_CONTROLS); значим только при BY_CHOICE. */
    val byChoiceMode: String = "SCORE",
    /** Собственное КВ группы в минутах (null — наследуется от соревнования). Для BY_CHOICE — лимит времени. */
    val timeLimitMinutes: Int? = null,
    /** Итоговое контрольное время группы: `timeLimitMinutes ?: competition.controlTimeMinutes`. */
    val controlTimeMinutes: Int? = null,
    /** true, если [controlTimeMinutes] унаследовано от соревнования, а не задано у группы. */
    val controlTimeInherited: Boolean = false,
    /** Штраф в очках за минуту опоздания сверх лимита (BY_CHOICE). */
    val scorePenaltyPerMinute: Int? = null,
    /** Порог сильного опоздания, после которого результат обнуляется (BY_CHOICE). */
    val maxLatenessMinutes: Int? = null,
    /** Своё N командного зачёта группы; null — как у соревнования. */
    val teamCountedResults: Int? = null
)

data class CompetitionDetailResponse(
    val id: String,
    val legacyId: Long? = null,
    val title: String,
    val startDate: Long,
    val endDate: Long?,
    val kindOfSport: String,
    val description: String?,
    val address: String?,
    val mainOrganizerId: String?,
    val organizingClubId: String? = null,
    val organizerFirstName: String? = null,
    val organizerLastName: String? = null,
    val organizerMiddleName: String? = null,
    /** Организатор произвольным текстом — независим от аккаунта, привязанного к mainOrganizerId. */
    val organizerName: String? = null,
    val coordinates: CoordinatesResponse?,
    val status: String,
    val startTime: Long? = null,
    /** "FORWARD" / "BY_CHOICE" / "MARKING" — направление ориентирования. */
    val direction: String = "FORWARD",
    /** Контрольное время соревнования в минутах — умолчание для групп без своего КВ. */
    val controlTimeMinutes: Int? = null,
    /** [com.competra.domain.orienteering.OvertimePolicy]: IGNORE / DISQUALIFY / SCORE_PENALTY — для формата по умолчанию (legacy). */
    val overtimePolicy: String = "IGNORE",
    /** Политика КВ для дистанций, где места по времени: IGNORE / DISQUALIFY. */
    val timeOvertimePolicy: String = "IGNORE",
    /** Политика КВ для дистанций с баллами: IGNORE / DISQUALIFY / SCORE_PENALTY. */
    val scoreOvertimePolicy: String = "SCORE_PENALTY",
    /** true, если у какой-то дистанции эффективный формат отличается от формата соревнования. */
    val isMixedFormat: Boolean = false,
    /** [com.competra.domain.orienteering.ByChoiceMode]: SCORE / MIN_CONTROLS (значим только для BY_CHOICE). */
    val byChoiceMode: String = "SCORE",
    /** Настройки командного зачёта; null — зачёта нет. */
    val teamScoring: TeamScoringResponse? = null,
    /** Режим «Регистрация на финише»: участники заводятся при считывании чипа на финише. */
    val isFinishRegistration: Boolean = false,
    val registrationStart: Long?,
    val registrationEnd: Long?,
    val maxParticipants: Int?,
    val feeAmount: Double?,
    val feeCurrency: String?,
    val imageUrl: String? = null,
    val coverCropX: Double? = null,
    val coverCropY: Double? = null,
    val coverCropWidth: Double? = null,
    val coverCropHeight: Double? = null,
    val regulationUrl: String?,
    val mapUrl: String?,
    val resultsUrl: String? = null,
    val contactPhone: String?,
    val contactEmail: String?,
    val website: String?,
    val resultsStatus: String,
    val timeZoneId: String,
    val participantGroups: List<ParticipantGroupDetailResponse>,
    val isUserRegistered: Boolean = false,
    /**
     * Зритель (по Bearer-токену) — организатор соревнования с любой ролью. Клиенты показывают ему
     * онлайн-треки до старта — проверить, что трекинг работает; остальным — только после старта.
     */
    val viewerIsOrganizer: Boolean = false,
    /** Тестовое соревнование: видно только владельцу, исключено из публичной ленты. */
    val isTest: Boolean = false,
    val updatedAt: Long = 0L
)
