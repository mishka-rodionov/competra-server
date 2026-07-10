package com.competra.data.util

import com.competra.data.requests.orienteering.ControlPointRequest
import com.competra.data.requests.orienteering.DistanceRequest
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

object IOFXmlParser {

    fun parse(xmlBytes: ByteArray, competitionId: String): List<DistanceRequest> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(xmlBytes.inputStream())
        val rcd = doc.documentElement
            .getElementsByTagName("RaceCourseData").item(0) as? Element
            ?: return emptyList()

        // Справочник КП объявлен как прямые дети RaceCourseData: <Control><Id/><Position lat lng/></Control>.
        // getElementsByTagName рекурсивен и задел бы также ссылки <Control> внутри CourseControl,
        // поэтому справочник собираем только по прямым потомкам.
        val controlPositions = mutableMapOf<String, Pair<Double, Double>>()
        val rcdChildren = rcd.childNodes
        for (k in 0 until rcdChildren.length) {
            val node = rcdChildren.item(k) as? Element ?: continue
            if (node.tagName != "Control") continue
            val id = node.getElementsByTagName("Id").item(0)?.textContent ?: continue
            val position = node.getElementsByTagName("Position").item(0) as? Element ?: continue
            val lat = position.getAttribute("lat").toDoubleOrNull()
            val lng = position.getAttribute("lng").toDoubleOrNull()
            if (lat != null && lng != null) controlPositions[id] = lat to lng
        }

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
                val position = controlPositions[ctrlId]
                ControlPointRequest(
                    number = code ?: j,
                    role = role,
                    score = 0,
                    latitude = position?.first,
                    longitude = position?.second
                )
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
