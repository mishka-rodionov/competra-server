package com.sportenth

import com.sportenth.data.response.base.BaseError
import com.sportenth.data.response.base.CommonModel
import com.sportenth.data.response.upload.UploadResponse
import com.sportenth.data.routing.orienteeringPublicRoutes
import com.sportenth.data.routing.orienteeringRoutes
import com.sportenth.data.services.DistanceService
import com.sportenth.data.services.OrienteeringCompetitionService
import com.sportenth.data.services.OrienteeringParticipantService
import com.sportenth.data.services.OrienteeringResultService
import com.sportenth.data.services.ParticipantGroupService
import com.sportenth.data.services.UploadService
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.utils.io.toByteArray
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Application.configureRouting() {
    install(AutoHeadResponse)

    val competitionService = OrienteeringCompetitionService()
    val groupService = ParticipantGroupService()
    val participantService = OrienteeringParticipantService()
    val resultService = OrienteeringResultService()
    val distanceService = DistanceService()
    val uploadService = UploadService()

    routing {
        get("/info") {
            call.respondText("Hello World!")
        }

        orienteeringPublicRoutes(competitionService, participantService, resultService)

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
        }
    }
}
