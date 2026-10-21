package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.captureException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Shared across clients and modes; immediate operation routing is a separate concern. */
private class TransactionInProgress : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TransactionInProgress>
}

internal suspend fun <T> withoutNestedTransaction(block: suspend () -> T): T {
    val context = currentCoroutineContext()
    context.ensureActive()
    check(context[TransactionInProgress] == null) { "Nested transactions are not supported" }
    // Keep ordinary failure identity and suppressed cleanup errors across coroutine recovery.
    return withContext(TransactionInProgress()) { captureException { block() } }.getOrThrow()
}
