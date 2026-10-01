package com.competra.data.response.orienteering

import com.competra.domain.user.Gender
import com.google.gson.annotations.SerializedName

/** Непривязанный участник, похожий по имени на текущего пользователя. */
data class LinkSuggestionResponse(
    @SerializedName("participantId") val participantId: String,
    @SerializedName("competitionId") val competitionId: String,
    @SerializedName("competitionTitle") val competitionTitle: String,
    @SerializedName("competitionStartDate") val competitionStartDate: Long,
    @SerializedName("firstName") val firstName: String,
    @SerializedName("lastName") val lastName: String,
    @SerializedName("groupName") val groupName: String,
    @SerializedName("commandName") val commandName: String?,
    @SerializedName("result") val result: LinkResultSummary?
)

/** Коротко о результате участника — чтобы человек узнал свой старт. */
data class LinkResultSummary(
    @SerializedName("rank") val rank: Int?,
    @SerializedName("totalTime") val totalTime: Long?,
    @SerializedName("totalScore") val totalScore: Int?,
    @SerializedName("status") val status: String
)

/** Заявка глазами заявителя. */
data class LinkRequestResponse(
    @SerializedName("id") val id: String,
    @SerializedName("participantId") val participantId: String,
    @SerializedName("competitionId") val competitionId: String,
    @SerializedName("competitionTitle") val competitionTitle: String,
    @SerializedName("competitionStartDate") val competitionStartDate: Long,
    @SerializedName("participantFirstName") val participantFirstName: String,
    @SerializedName("participantLastName") val participantLastName: String,
    @SerializedName("groupName") val groupName: String,
    @SerializedName("status") val status: String,
    @SerializedName("source") val source: String,
    @SerializedName("comment") val comment: String?,
    @SerializedName("createdAt") val createdAt: Long
)

/** Заявка глазами организатора — с подсказками для проверки. Email и телефон заявителя не отдаём. */
data class CompetitionLinkRequestResponse(
    @SerializedName("id") val id: String,
    @SerializedName("status") val status: String,
    @SerializedName("source") val source: String,
    @SerializedName("comment") val comment: String?,
    @SerializedName("createdAt") val createdAt: Long,
    @SerializedName("participantId") val participantId: String,
    @SerializedName("participantFirstName") val participantFirstName: String,
    @SerializedName("participantLastName") val participantLastName: String,
    @SerializedName("groupName") val groupName: String,
    @SerializedName("commandName") val commandName: String?,
    @SerializedName("startNumber") val startNumber: Int,
    @SerializedName("result") val result: LinkResultSummary?,
    @SerializedName("userId") val userId: String,
    @SerializedName("userFirstName") val userFirstName: String,
    @SerializedName("userLastName") val userLastName: String,
    @SerializedName("userBirthYear") val userBirthYear: Int?,
    @SerializedName("userGender") val userGender: Gender?,
    /** Имя в протоколе совпадает с профилем (false — повод присмотреться к MANUAL-заявке). */
    @SerializedName("nameMatches") val nameMatches: Boolean,
    /** Текст, если пол/возраст заявителя не подходят к группе участника; null — подходят. */
    @SerializedName("eligibilityWarning") val eligibilityWarning: String?,
    /** Сколько ещё PENDING-заявок на этого же участника от других пользователей. */
    @SerializedName("competingRequests") val competingRequests: Int,
    /** У заявителя уже есть свой участник в этом соревновании — одобрить нельзя, нужно удалить дубль. */
    @SerializedName("userAlreadyInCompetition") val userAlreadyInCompetition: Boolean
)
