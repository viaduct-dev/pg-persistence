package dev.viaduct.persistence.runtime

import java.util.concurrent.CancellationException

/** Captures recoverable exceptions while allowing cancellation and JVM errors to propagate. */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> captureException(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        Result.failure(failure)
    }
