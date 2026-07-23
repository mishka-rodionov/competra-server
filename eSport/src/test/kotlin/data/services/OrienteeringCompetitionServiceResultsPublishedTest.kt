package com.competra.data.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrienteeringCompetitionServiceResultsPublishedTest {

    @Test
    fun `NOT_PUBLISHED to PRELIMINARY triggers notification`() {
        assertTrue(shouldNotifyResultsPublished(oldStatus = "NOT_PUBLISHED", newStatus = "PRELIMINARY"))
    }

    @Test
    fun `NOT_PUBLISHED to OFFICIAL triggers notification`() {
        assertTrue(shouldNotifyResultsPublished(oldStatus = "NOT_PUBLISHED", newStatus = "OFFICIAL"))
    }

    @Test
    fun `new competition without prior status does not trigger notification`() {
        assertFalse(shouldNotifyResultsPublished(oldStatus = null, newStatus = "OFFICIAL"))
    }

    @Test
    fun `PRELIMINARY to OFFICIAL does not trigger notification again`() {
        assertFalse(shouldNotifyResultsPublished(oldStatus = "PRELIMINARY", newStatus = "OFFICIAL"))
    }

    @Test
    fun `OFFICIAL to OFFICIAL does not trigger notification`() {
        assertFalse(shouldNotifyResultsPublished(oldStatus = "OFFICIAL", newStatus = "OFFICIAL"))
    }

    @Test
    fun `reverse transition to NOT_PUBLISHED does not trigger notification`() {
        assertFalse(shouldNotifyResultsPublished(oldStatus = "OFFICIAL", newStatus = "NOT_PUBLISHED"))
    }

    @Test
    fun `unrelated edit while still NOT_PUBLISHED does not trigger notification`() {
        assertFalse(shouldNotifyResultsPublished(oldStatus = "NOT_PUBLISHED", newStatus = "NOT_PUBLISHED"))
    }
}
