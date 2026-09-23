package com.competra.data.events

import org.slf4j.LoggerFactory
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertTrue

class ResultEventPublisherTest {

    private val event = ResultSavedEvent("c", "p", "FINISHED", 1L, 2L)

    @Test
    fun `publishing with unreachable broker neither throws nor blocks the caller`() {
        // Порт без брокера: подключение отвалится в фоне, вызывающий (сохранение результата) не ждёт.
        val publisher = ResultEventPublisher("amqp://guest:guest@127.0.0.1:1", LoggerFactory.getLogger("test"))

        val elapsed = measureTimeMillis { repeat(100) { publisher.publishResultsSaved(listOf(event)) } }

        publisher.close()
        assertTrue(elapsed < 1_000, "publish blocked the caller for ${elapsed}ms")
    }

    @Test
    fun `publishing is a no-op without RABBITMQ_URI`() {
        ResultEventPublisher(null, LoggerFactory.getLogger("test")).publishResultsSaved(listOf(event))
    }
}
