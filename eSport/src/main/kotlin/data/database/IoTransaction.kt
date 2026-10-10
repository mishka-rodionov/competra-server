package com.competra.data.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Suspend-транзакция Exposed на IO-диспетчере — замена `newSuspendedTransaction(Dispatchers.IO)`,
 * устаревшей в Exposed 1.x. В отличие от неё, `suspendTransaction` при вызове внутри уже открытой
 * транзакции присоединяется к ней (как `transaction {}`), а не открывает новую top-level.
 */
suspend fun <T> ioTransaction(block: suspend JdbcTransaction.() -> T): T =
    withContext(Dispatchers.IO) { suspendTransaction { block() } }
