package com.competra.data.exception

/**
 * Бросается из сервисов upsert, когда клиентский serverUpdatedAt отстал от сервера.
 *
 * Согласно server-wins политике, routing должен поймать это исключение и вернуть HTTP 409
 * с актуальным response в теле, чтобы клиент мог перезаписать локалку серверными данными.
 *
 * @property currentResponse Актуальная серверная запись для отдачи клиенту.
 * @property clientUpdatedAt Значение serverUpdatedAt, пришедшее от клиента.
 * @property serverUpdatedAt Значение updatedAt, хранящееся на сервере.
 */
class ConflictException(
    val currentResponse: Any,
    val clientUpdatedAt: Long,
    val serverUpdatedAt: Long
) : RuntimeException(
    "Server record is newer: serverUpdatedAt=$serverUpdatedAt > clientUpdatedAt=$clientUpdatedAt"
)
