package com.competra.data.util

import com.competra.data.requests.orienteering.ControlPointRequest
import com.competra.data.requests.orienteering.DistanceRequest
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

object IOFXmlParser {

    fun parse(xmlBytes: ByteArray, competitionId: Long): List<DistanceRequest> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(xmlBytes.inputStream())
        val rcd = doc.documentElement
            .getElementsByTagName("RaceCourseData").item(0) as? Element
            ?: return emptyList()

        val courses = rcd.getElementsByTagName("Course")
        return (0 until courses.length).map { i ->
            val course = courses.item(i) as Element
            val name   = course.getElementsByTagName("Name").item(0)?.textContent
            val length = course.getElementsByTagName("Length").item(0)?.textContent?.toIntOrNull() ?: 0
            val climb  = course.getElementsByTagName("Climb").item(0)?.textContent?.toIntOrNull() ?: 0

            val courseControls = course.getElementsByTagName("CourseControl")
            var finishNumber: Int? = null
            val controlPoints = (0 until courseControls.length).map { j ->
                val cc     = courseControls.item(j) as Element
                val type   = cc.getAttribute("type")
                val ctrlId = cc.getElementsByTagName("Control").item(0)?.textContent ?: ""
                val code   = ctrlId.filter { it.isDigit() }.toIntOrNull()
                val role   = when (type) { "Start" -> "Start"; "Finish" -> "Finish"; else -> "ORDINARY" }
                if (role == "Finish" && code != null) finishNumber = code
                ControlPointRequest(number = code ?: j, role = role, score = 0)
            }

            DistanceRequest(
                distanceId = null,
                competitionId = competitionId,
                name = name,
                lengthMeters = length,
                climbMeters = climb,
                controlsCount = controlPoints.size,
                description = null,
                controlPoints = controlPoints,
                finishControlPoint = finishNumber,
                serverUpdatedAt = null
            )
        }
    }
}
