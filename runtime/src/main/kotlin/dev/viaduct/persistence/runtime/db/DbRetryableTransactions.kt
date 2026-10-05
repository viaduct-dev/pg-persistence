package dev.viaduct.persistence.runtime.db

import viaduct.api.context.ExecutionContext

/** Derives a tenant/caller scope from trusted server identity, never directly from a request argument. */
fun interface DbTransactionIdentity {
    suspend fun scope(context: ExecutionContext): String
}

/** Opt-in retry configuration. Business authorization remains the application's responsibility. */
class DbRetryableTransactions(
    val identity: DbTransactionIdentity,
    val maxAttempts: Int = 3,
    val timeoutMillis: Long = 30_000,
    val initialDelayMillis: Long = 100,
) {
    init {
        require(maxAttempts in 1..MAX_ATTEMPTS) { "maxAttempts must be between 1 and $MAX_ATTEMPTS" }
        require(timeoutMillis in 1..MAX_TIMEOUT) { "timeoutMillis must be between 1 and $MAX_TIMEOUT" }
        require(initialDelayMillis in 0..MAX_DELAY) { "initialDelayMillis must be between 0 and $MAX_DELAY" }
    }

    internal fun delayCeiling(attempt: Int): Long =
        (initialDelayMillis * (1L shl attempt.coerceAtMost(MAX_EXPONENT))).coerceAtMost(MAX_DELAY)

    internal fun canRetry(sqlState: String?): Boolean = sqlState in TRANSIENT_SQL_STATES

    private companion object {
        const val MAX_ATTEMPTS = 100
        const val MAX_TIMEOUT = 300_000L
        const val MAX_DELAY = 10_000L
        const val MAX_EXPONENT = 10
        private val TRANSIENT_SQL_STATES = setOf("40001", "40P01", "55P03")
    }
}

/** A recovery result is a historical database response, not a freshly executed Viaduct payload. */
class DbRecoveredTransaction internal constructor(
    val prepared: DbPreparedTransaction,
    val result: DbTransactionResult,
)

enum class DbTransactionOutcome {
    /** No definitive response was received; recover using the same prepared request. */
    UNKNOWN,

    /** The database confirmed commit, even if the local result could not be decoded. */
    COMMITTED,

    /** This submission was rejected. An earlier attempt could still have committed. */
    REJECTED,
}

/** Retain [prepared] when the outcome is unknown; do not retry using a new operation ID. */
class DbTransactionException internal constructor(
    val outcome: DbTransactionOutcome,
    val prepared: DbPreparedTransaction,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
