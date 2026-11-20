package dev.nura.app.field.sync.core.util

import kotlin.coroutines.cancellation.CancellationException

/** Like [runCatching], but rethrows [CancellationException] so coroutine cancellation still works. */
inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
