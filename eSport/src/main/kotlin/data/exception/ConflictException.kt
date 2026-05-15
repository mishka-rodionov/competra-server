package com.competra.data.exception

/**
 * Бросается из сервисов upsert, когда клиентский serverUpdatedAt отстал от сервера.
 *
 * Согласно server-wins политике, routing должен поймать это исключение и вернуть HTTP 409
 * с актуальным response в теле, чтобы клиент мог перезаписать локалку серверными данными.
 *
 * @property currentResponse Актуальная серверная запись для отдачи клиенту.
 */
class ConflictException(val currentResponse: Any) : RuntimeException(
    "Server record has a newer updatedAt than client request"
)
