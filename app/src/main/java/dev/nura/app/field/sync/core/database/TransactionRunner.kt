package dev.nura.app.field.sync.core.database

import androidx.room.withTransaction
import javax.inject.Inject

/** Runs a block atomically. Abstracted so repositories can be unit tested with fake DAOs. */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

class RoomTransactionRunner @Inject constructor(
    private val database: AppDatabase,
) : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction(block)
}
