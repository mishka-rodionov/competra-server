package com.competra.data.util

import com.competra.data.requests.orienteering.ControlPointRequest
import com.competra.data.requests.orienteering.DistanceRequest
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

object IOFXmlParser {

    /**
     * В [DistanceRequest.controlPoints] попадают только КП, отмечаемые по ходу дистанции.
     * Финиш хранится отдельно в [DistanceRequest.finishControlPoint] (клиенты сами добавляют
     * его в конец списка), старт — в [DistanceRequest.startControlPoint], причём только если
     * [useStartStation] (режим старта BY_START_STATION): в остальных режимах стартовой
     * станции нет и её номер не нужен. Координаты старта и финиша сохраняются всегда (если есть
     * в справочнике) — по ним клиенты считают длину первого и последнего перегона.
     *
     * Правила формата «по выбору», для которых в IOF 3.0 нет элементов, Mapper пишет в стандартные
     * `<Extensions>`:
     *  - `<CourseControl>…<Extensions><Required>true</Required></Extensions>` — обязательный КП
     *    (роль "required");
     *  - `<Course>…<Extensions><FreeOrder minControls="18"/></Extensions>` — дистанция в свободном
     *    порядке с минимумом КП; без `minControls` — взять все КП ([DistanceRequest.minControlsCount] = 0).
     *    Без `<FreeOrder>` минимум не задаётся (null).
     */
    fun parse(xmlBytes: ByteArray, competitionId: String, useStartStation: Boolean = false): List<DistanceRequest> {
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
            var startNumber: Int? = null
            var startPosition: Pair<Double, Double>? = null
            var finishPosition: Pair<Double, Double>? = null
            val controlPoints = mutableListOf<ControlPointRequest>()
            for (j in 0 until courseControls.length) {
                val cc     = courseControls.item(j) as Element
                val type   = cc.getAttribute("type")
                val ctrlId = cc.getElementsByTagName("Control").item(0)?.textContent ?: ""
                val code   = ctrlId.filter { it.isDigit() }.toIntOrNull()
                when (type) {
                    "Start" -> {
                        if (useStartStation && code != null) startNumber = code
                        startPosition = controlPositions[ctrlId]
                    }
                    "Finish" -> {
                        if (code != null) finishNumber = code
                        finishPosition = controlPositions[ctrlId]
                    }
                    else -> {
                        val position = controlPositions[ctrlId]
                        val score = cc.getElementsByTagName("Score").item(0)?.textContent?.toIntOrNull() ?: 0
                        val required = cc.getElementsByTagName("Required").item(0)
                            ?.textContent?.trim()?.equals("true", ignoreCase = true) == true
                        // Роль должна совпадать с ControlPointRole на Android (Gson @SerializedName
                        // там в нижнем регистре) — иначе Gson роняет NPE при сборке модели дистанции.
                        controlPoints += ControlPointRequest(
                            number = code ?: j,
                            role = if (required) "required" else "ordinary",
                            score = score,
                            latitude = position?.first,
                            longitude = position?.second
                        )
                    }
                }
            }

            // getElementsByTagName рекурсивен, но FreeOrder встречается только в Extensions курса.
            val freeOrder = course.getElementsByTagName("FreeOrder").item(0) as? Element
            val minControlsCount = freeOrder?.let { it.getAttribute("minControls").toIntOrNull() ?: 0 }

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
                startControlPoint = startNumber,
                startLatitude = startPosition?.first,
                startLongitude = startPosition?.second,
                finishLatitude = finishPosition?.first,
                finishLongitude = finishPosition?.second,
                minControlsCount = minControlsCount,
                serverUpdatedAt = null
            )
        }
    }
}
