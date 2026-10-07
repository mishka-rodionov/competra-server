package com.competra.data.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Проверяет парсинг курса IOF XML 3.0, экспортируемого mapper (см.
 * mapper/test/data/export/iof-3.0-course.xml) — в частности, что координаты КП из
 * справочника RaceCourseData/Control попадают в ControlPointRequest.latitude/longitude,
 * а не теряются, как было раньше.
 */
class IOFXmlParserTest {

    private val sampleXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <CourseData xmlns="http://www.orienteering.org/datastandard/3.0" iofVersion="3.0" creator="test" createTime="2021-05-20T09:28:32">
            <Event>
                <Name>Test event</Name>
            </Event>
            <RaceCourseData>
                <Control>
                    <Id>S1</Id>
                    <Position lng="8" lat="51"/>
                </Control>
                <Control>
                    <Id>101</Id>
                    <Position lng="8.003674" lat="50.9998836"/>
                </Control>
                <Control>
                    <Id>F1</Id>
                    <Position lng="8.0071138" lat="50.9997745"/>
                </Control>
                <Course>
                    <Name>Test course</Name>
                    <Length>500</Length>
                    <CourseControl type="Start">
                        <Control>S1</Control>
                    </CourseControl>
                    <CourseControl type="Control">
                        <Control>101</Control>
                    </CourseControl>
                    <CourseControl type="Finish">
                        <Control>F1</Control>
                    </CourseControl>
                </Course>
            </RaceCourseData>
        </CourseData>
    """.trimIndent()

    @Test
    fun `parses coordinates from the RaceCourseData control catalog`() {
        val result = IOFXmlParser.parse(sampleXml.toByteArray(), competitionId = "c1")

        assertEquals(1, result.size)
        val distance = result.first()
        assertEquals("Test course", distance.name)

        val ordinary = distance.controlPoints.single()
        assertEquals("ordinary", ordinary.role)
        assertEquals(101, ordinary.number)
        assertEquals(50.9998836, ordinary.latitude)
        assertEquals(8.003674, ordinary.longitude)
    }

    @Test
    fun `start and finish are kept out of the control point list`() {
        val distance = IOFXmlParser.parse(sampleXml.toByteArray(), competitionId = "c1").first()

        assertEquals(listOf(101), distance.controlPoints.map { it.number })
        assertEquals(1, distance.controlsCount)
        assertEquals(1, distance.finishControlPoint)
    }

    @Test
    fun `start control point is set only for start station mode`() {
        val withoutStation = IOFXmlParser.parse(sampleXml.toByteArray(), competitionId = "c1")
        assertNull(withoutStation.first().startControlPoint)

        val withStation = IOFXmlParser.parse(sampleXml.toByteArray(), competitionId = "c1", useStartStation = true)
        assertEquals(1, withStation.first().startControlPoint)
        assertEquals(listOf(101), withStation.first().controlPoints.map { it.number })
    }

    @Test
    fun `start and finish coordinates are kept regardless of start station mode`() {
        val distance = IOFXmlParser.parse(sampleXml.toByteArray(), competitionId = "c1").first()

        assertEquals(51.0, distance.startLatitude)
        assertEquals(8.0, distance.startLongitude)
        assertEquals(50.9997745, distance.finishLatitude)
        assertEquals(8.0071138, distance.finishLongitude)
    }

    @Test
    fun `control without position keeps null coordinates`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <CourseData xmlns="http://www.orienteering.org/datastandard/3.0" iofVersion="3.0">
                <RaceCourseData>
                    <Control>
                        <Id>101</Id>
                    </Control>
                    <Course>
                        <Name>No geo course</Name>
                        <CourseControl type="Control">
                            <Control>101</Control>
                        </CourseControl>
                    </Course>
                </RaceCourseData>
            </CourseData>
        """.trimIndent()

        val distance = IOFXmlParser.parse(xml.toByteArray(), competitionId = "c1").first()

        assertNull(distance.controlPoints.single().latitude)
        assertNull(distance.controlPoints.single().longitude)
    }

    /** Курс с одним обязательным и одним обычным КП и необязательным блоком Extensions курса. */
    private fun freeOrderXml(courseExtensions: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <CourseData xmlns="http://www.orienteering.org/datastandard/3.0" iofVersion="3.0">
            <RaceCourseData>
                <Course>
                    <Name>M21</Name>
                    <CourseControl type="Control">
                        <Control>31</Control>
                        <Extensions><Required>true</Required></Extensions>
                    </CourseControl>
                    <CourseControl type="Control">
                        <Control>32</Control>
                    </CourseControl>
                    $courseExtensions
                </Course>
            </RaceCourseData>
        </CourseData>
    """.trimIndent()

    @Test
    fun `required control from extensions gets the required role`() {
        val distance = IOFXmlParser.parse(freeOrderXml("").toByteArray(), competitionId = "c1").first()

        assertEquals(listOf("required", "ordinary"), distance.controlPoints.map { it.role })
    }

    @Test
    fun `free order course keeps its minimum number of controls`() {
        val xml = freeOrderXml("""<Extensions><FreeOrder minControls="1"/></Extensions>""")
        val distance = IOFXmlParser.parse(xml.toByteArray(), competitionId = "c1").first()

        assertEquals(1, distance.minControlsCount)
    }

    @Test
    fun `free order course without minimum means all controls`() {
        val xml = freeOrderXml("""<Extensions><FreeOrder/></Extensions>""")
        val distance = IOFXmlParser.parse(xml.toByteArray(), competitionId = "c1").first()

        assertEquals(0, distance.minControlsCount)
    }

    @Test
    fun `course without free order leaves the minimum unset`() {
        val distance = IOFXmlParser.parse(freeOrderXml("").toByteArray(), competitionId = "c1").first()

        assertNull(distance.minControlsCount)
    }
}
