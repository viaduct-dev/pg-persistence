package dev.viaduct.persistence.jdbc

/** Captures ordinary exceptions while allowing JVM errors to propagate. */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> captureException(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (failure: Exception) {
        Result.failure(failure)
    }
