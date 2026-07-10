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
        assertEquals(3, distance.controlPoints.size)

        val (start, ordinary, finish) = distance.controlPoints

        assertEquals("Start", start.role)
        assertEquals(51.0, start.latitude)
        assertEquals(8.0, start.longitude)

        assertEquals("ORDINARY", ordinary.role)
        assertEquals(101, ordinary.number)
        assertEquals(50.9998836, ordinary.latitude)
        assertEquals(8.003674, ordinary.longitude)

        assertEquals("Finish", finish.role)
        assertEquals(50.9997745, finish.latitude)
        assertEquals(8.0071138, finish.longitude)
        assertEquals(finish.number, distance.finishControlPoint)
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
}
