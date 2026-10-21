package dev.viaduct.persistence

/** Lets tests inspect ordinary failures, including expected cancellation, without consuming JVM errors. */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> captureTestException(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (failure: Exception) {
        Result.failure(failure)
    }
