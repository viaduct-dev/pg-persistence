package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import viaduct.api.context.ExecutionContext
import java.io.IOException
import kotlin.random.Random

/** Retries only frozen requests, never application code. */
internal class RetryableTransactionExecutor(
    transport: PgGraphqlTransport,
    private val headers: DbRequestHeaders,
    private val settings: DbRetryableTransactions,
) {
    private val protocol = RetryableTransactionProtocol(transport)

    suspend fun prepare(
        ctx: ExecutionContext,
        operationId: String,
        transaction: PreparedTransaction,
    ): DbPreparedTransaction =
        DbPreparedTransaction.create(
            operationId,
            requireNotNull(settings.identity.scope(ctx)),
            transaction.retryRequest(),
            transaction.operationCount,
            transaction.transactionId,
        )

    suspend fun execute(
        ctx: ExecutionContext,
        prepared: DbPreparedTransaction,
    ): DbResult<DbTransactionResult> {
        var failure: Throwable? = null
        val result =
            withTimeoutOrNull(settings.timeoutMillis) {
                repeat(settings.maxAttempts) { attempt ->
                    val response = attempt(requestHeaders(ctx, prepared.scope), prepared)
                    response.exceptionOrNull()?.let { failure = it }
                    val completed = response.getOrNull()?.let { complete(it, prepared) }
                    if (completed != null) return@withTimeoutOrNull completed
                    if (attempt + 1 < settings.maxAttempts) {
                        val ceiling = settings.delayCeiling(attempt)
                        if (ceiling > 0) delay(Random.nextLong(ceiling / 2, ceiling + 1))
                    }
                }
                null
            }
        return result ?: throw DbTransactionException(
            DbTransactionOutcome.UNKNOWN,
            prepared,
            "Transaction outcome is unknown; recover using the same operation ID and prepared request",
            failure,
        )
    }

    suspend fun lookup(
        ctx: ExecutionContext,
        operationId: String,
    ): DbRecoveredTransaction? {
        val scope = requireNotNull(settings.identity.scope(ctx))
        return protocol.lookup(requestHeaders(ctx, scope), operationId, scope)
    }

    private suspend fun requestHeaders(
        ctx: ExecutionContext,
        expectedScope: String,
    ): Map<String, String> {
        require(settings.identity.scope(ctx) == expectedScope) { "Prepared transaction belongs to a different scope" }
        val scopeHeader = RetryableTransactionProtocol.SCOPE_HEADER
        return requireNotNull(headers.forContext(ctx)).filterKeys { !it.equals(scopeHeader, ignoreCase = true) } +
            (scopeHeader to expectedScope)
    }

    private suspend fun attempt(
        headers: Map<String, String>,
        prepared: DbPreparedTransaction,
    ): Result<RetryableTransactionReply> =
        runCatching { protocol.execute(headers, prepared) }.onFailure { failure ->
            if (failure is UpstreamGraphqlException) {
                throw DbTransactionException(
                    DbTransactionOutcome.REJECTED,
                    prepared,
                    "pg_graphql rejected retryable transaction execution",
                    failure,
                )
            }
            if (!failure.isRetryableTransportFailure()) throw failure
        }

    private fun complete(
        reply: RetryableTransactionReply,
        prepared: DbPreparedTransaction,
    ): DbResult<DbTransactionResult>? =
        when (reply) {
            is RetryableTransactionReply.Committed -> DbResult(reply.result)
            is RetryableTransactionReply.RolledBack ->
                if (settings.canRetry(reply.sqlState)) null else DbResult(null, reply.errors)
            RetryableTransactionReply.Busy -> null
            RetryableTransactionReply.Mismatch -> throw DbTransactionException(
                DbTransactionOutcome.REJECTED,
                prepared,
                "Operation ID was already used for a different request",
            )
        }
}

/** Cancellation, confirmed outcomes, and unrecognized failures must never become retry attempts. */
private fun Throwable.isRetryableTransportFailure(): Boolean =
    this !is CancellationException &&
        this !is DbTransactionException &&
        (this is IOException || this is SerializationException || this is IllegalStateException)
