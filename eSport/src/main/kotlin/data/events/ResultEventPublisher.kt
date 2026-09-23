package com.competra.data.events

import com.google.gson.Gson
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.MessageProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.Logger

/** Сколько не пытаться переподключиться после неудачи (события в это время отбрасываются). */
private const val RECONNECT_BACKOFF_MS = 30_000L

/**
 * Публикует события основного приложения в RabbitMQ по принципу «выстрелил и забыл».
 *
 * Сохранение результата **никогда** не зависит от RabbitMQ: публикация идёт после коммита, в своём
 * фоновом scope на одном потоке, любые ошибки только логируются. Если брокер недоступен, события
 * за [RECONNECT_BACKOFF_MS] отбрасываются без попыток подключения — чтобы не копить очередь
 * блокирующих коннектов. Потребитель (трекинг) в этом случае закроет сессии по таймауту.
 *
 * @param uri `RABBITMQ_URI`; `null` — публикация отключена.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResultEventPublisher(private val uri: String?, private val log: Logger) {

    private val gson = Gson()

    // Один поток: канал RabbitMQ не потокобезопасен, а так к нему не нужна отдельная синхронизация.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private var connection: Connection? = null
    private var channel: Channel? = null
    private var retryNotBefore = 0L

    /** Публикует `result.saved` для каждого события. Не бросает исключений. */
    fun publishResultsSaved(events: List<ResultSavedEvent>) {
        if (uri == null || events.isEmpty()) return
        scope.launch {
            val ch = channelOrNull() ?: return@launch
            runCatching {
                events.forEach { event ->
                    ch.basicPublish(
                        CompetraEvents.EXCHANGE,
                        CompetraEvents.RESULT_SAVED,
                        MessageProperties.PERSISTENT_TEXT_PLAIN,
                        gson.toJson(event).toByteArray(Charsets.UTF_8)
                    )
                }
            }.onFailure {
                log.warn("ResultEventPublisher: publish failed, ${events.size} event(s) dropped", it)
                reset()
            }
        }
    }

    /** Закрывает соединение при остановке приложения. */
    fun close() {
        scope.cancel()
        reset()
    }

    private fun channelOrNull(): Channel? {
        channel?.takeIf { it.isOpen }?.let { return it }
        if (System.currentTimeMillis() < retryNotBefore) return null
        return runCatching {
            reset()
            val factory = ConnectionFactory().apply {
                setUri(uri)
                isAutomaticRecoveryEnabled = true
                connectionTimeout = 3_000
                handshakeTimeout = 3_000
            }
            val newConnection = factory.newConnection("competra-main-events")
            val newChannel = newConnection.createChannel()
            newChannel.exchangeDeclare(CompetraEvents.EXCHANGE, "topic", true)
            connection = newConnection
            channel = newChannel
            newChannel
        }.onFailure {
            retryNotBefore = System.currentTimeMillis() + RECONNECT_BACKOFF_MS
            log.warn("ResultEventPublisher: RabbitMQ unavailable (${it.message}), events dropped for ${RECONNECT_BACKOFF_MS}ms")
        }.getOrNull()
    }

    private fun reset() {
        runCatching { connection?.close() }
        connection = null
        channel = null
    }
}
