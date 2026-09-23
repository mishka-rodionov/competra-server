package com.competra.tracking

import com.competra.data.events.CompetraEvents
import com.competra.data.events.ResultSavedEvent
import com.google.gson.Gson
import com.rabbitmq.client.CancelCallback
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DeliverCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger

/** Очередь трекинга для событий `result.saved`. */
private const val QUEUE = "live-track.result-saved"

/** Пауза между попытками первого подключения к RabbitMQ. */
private const val CONNECT_RETRY_MS = 30_000L

/**
 * Подписка процесса трекинга на `result.saved` из основного приложения: финальный результат
 * участника закрывает его онлайн-трек.
 *
 * Отказоустойчивость: трекинг стартует и работает и без RabbitMQ — первое подключение повторяется
 * в фоне раз в [CONNECT_RETRY_MS], дальше соединение и подписку восстанавливает сам клиент
 * (automatic recovery). Пока событий нет, сессии закрываются вручную или сторожем по таймауту.
 */
class ResultSavedConsumer(
    private val uri: String,
    private val service: LiveTrackService,
    private val log: Logger
) {
    private val gson = Gson()

    @Volatile
    private var connection: Connection? = null

    /** Запускает фоновое подключение и подписку. */
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive && connection == null) {
            runCatching { connection = subscribe() }
                .onSuccess { log.info("ResultSavedConsumer subscribed to $QUEUE") }
                .onFailure {
                    log.warn("ResultSavedConsumer: RabbitMQ unavailable (${it.message}), retry in ${CONNECT_RETRY_MS}ms")
                    delay(CONNECT_RETRY_MS)
                }
        }
    }

    /** Закрывает соединение при остановке процесса. */
    fun stop() {
        runCatching { connection?.close() }
    }

    private fun subscribe(): Connection {
        val factory = ConnectionFactory().apply {
            setUri(uri)
            isAutomaticRecoveryEnabled = true
            isTopologyRecoveryEnabled = true
            connectionTimeout = 5_000
            handshakeTimeout = 5_000
        }
        val newConnection = factory.newConnection("competra-tracking")
        try {
            val channel = newConnection.createChannel()
            channel.exchangeDeclare(CompetraEvents.EXCHANGE, "topic", true)
            channel.queueDeclare(QUEUE, true, false, false, null)
            channel.queueBind(QUEUE, CompetraEvents.EXCHANGE, CompetraEvents.RESULT_SAVED)
            channel.basicQos(20)
            val onDeliver = DeliverCallback { _, delivery ->
                val tag = delivery.envelope.deliveryTag
                runCatching {
                    val event = gson.fromJson(String(delivery.body, Charsets.UTF_8), ResultSavedEvent::class.java)
                    runBlocking { service.onResultSaved(event) }
                }.onSuccess {
                    channel.basicAck(tag, false)
                }.onFailure {
                    // Не переотправляем по кругу: сессию в худшем случае закроет сторож по таймауту.
                    log.warn("ResultSavedConsumer: failed to handle event, dropping", it)
                    channel.basicNack(tag, false, false)
                }
            }
            channel.basicConsume(QUEUE, false, onDeliver, CancelCallback { })
            return newConnection
        } catch (e: Exception) {
            runCatching { newConnection.close() }
            throw e
        }
    }
}
