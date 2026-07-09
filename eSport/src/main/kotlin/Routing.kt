package com.competra

import com.competra.data.response.base.BaseError
import com.competra.data.response.base.CommonModel
import com.competra.data.response.upload.UploadResponse
import com.competra.data.routing.clubsPublicRoutes
import com.competra.data.routing.clubsRoutes
import com.competra.data.routing.deviceRoutes
import com.competra.data.routing.diaryRoutes
import com.competra.data.routing.orienteeringPublicRoutes
import com.competra.data.routing.orienteeringRoutes
import com.competra.data.routing.ratingPublicRoutes
import com.competra.data.routing.ratingRoutes
import com.competra.data.routing.teamsPublicRoutes
import com.competra.data.routing.teamsRoutes
import com.competra.data.services.ClubJoinRequestService
import com.competra.data.services.ClubMemberService
import com.competra.data.services.ClubService
import com.competra.data.services.DeviceTokenService
import com.competra.data.services.DiaryWorkoutService
import com.competra.data.services.DistanceService
import com.competra.data.services.FcmService
import com.competra.data.services.OrienteeringCompetitionService
import com.competra.data.services.OrienteeringParticipantService
import com.competra.data.services.OrienteeringResultService
import com.competra.data.services.ParticipantGroupService
import com.competra.data.services.RatingService
import com.competra.data.services.TeamMemberService
import com.competra.data.services.TeamService
import com.competra.data.services.UploadService
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.utils.io.toByteArray
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import io.ktor.server.auth.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val FcmServiceKey = AttributeKey<FcmService>("FcmService")

fun Application.configureRouting() {
    install(AutoHeadResponse)

    val competitionService = OrienteeringCompetitionService()
    val groupService = ParticipantGroupService()
    val participantService = OrienteeringParticipantService()
    val resultService = OrienteeringResultService()
    val distanceService = DistanceService()
    val uploadService = UploadService()
    val deviceTokenService = DeviceTokenService()
    val fcmService = FcmService(deviceTokenService)
    val diaryWorkoutService = DiaryWorkoutService()
    val clubService = ClubService()
    val clubMemberService = ClubMemberService()
    val clubJoinRequestService = ClubJoinRequestService()
    val teamService = TeamService()
    val teamMemberService = TeamMemberService()
    val ratingService = RatingService()
    attributes.put(FcmServiceKey, fcmService)

    routing {
        // /health, /health/ready остаются на корне для внешних мониторингов (UptimeRobot и т.д.)
        healthRoutes()

        route("/api") {
            orienteeringPublicRoutes(competitionService, participantService, resultService, groupService)
            clubsPublicRoutes(clubService, clubMemberService)
            teamsPublicRoutes(teamService, teamMemberService)
            ratingPublicRoutes(ratingService)

            authenticate("auth-jwt") {
                post("/upload/file") {
                    val multipart = call.receiveMultipart()
                    var fileBytes: ByteArray? = null
                    var fileName = "upload"
                    var type = "avatar"
                    var contentType = "application/octet-stream"

                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FileItem -> {
                                fileName = part.originalFileName ?: "upload"
                                contentType = part.contentType?.toString() ?: "application/octet-stream"
                                fileBytes = part.provider().toByteArray()
                            }
                            is PartData.FormItem -> {
                                if (part.name == "type") type = part.value
                            }
                            else -> {}
                        }
                        part.dispose()
                    }

                    val bytes = fileBytes
                    if (bytes == null) {
                        call.respond(
                            CommonModel<Any>().also {
                                it.status = 0
                                it.errors = listOf(BaseError(400, "No file provided"))
                            }
                        )
                        return@post
                    }

                    val url = withContext(Dispatchers.IO) {
                        uploadService.upload(bytes, fileName, type, contentType)
                    }

                    call.respond(
                        CommonModel<UploadResponse>().also { it.status = 1; it.result = UploadResponse(url) }
                    )
                }

                orienteeringRoutes(competitionService, groupService, participantService, resultService, distanceService)
                deviceRoutes(deviceTokenService, fcmService)
                diaryRoutes(diaryWorkoutService)
                clubsRoutes(clubService, clubMemberService, clubJoinRequestService)
                teamsRoutes(teamService, teamMemberService)
                ratingRoutes(ratingService)
            }
        }
    }
}
