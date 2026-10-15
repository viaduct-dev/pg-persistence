package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.captureException
import dev.viaduct.persistence.runtime.graphql.PgGraphqlRequest
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import viaduct.deferred.RequestParentJobContextElement
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Optional execution of the existing transaction lambda, selected when constructing [DbClient]. */
interface DbTransactions {
    suspend fun <T> execute(
        headers: Map<String, String>,
        block: suspend DbTransactionScope.() -> T,
    ): DbTransactionCommit<T>
}

/** Adapts a callback-based transaction implementation without leaking its threading into [DbClient]. */
abstract class BlockingDbTransactions
    @JvmOverloads
    constructor(
        parallelism: Int = 16,
    ) : DbTransactions,
        AutoCloseable {
        private val permits = Semaphore(parallelism)
        private val ownerDispatcher =
            Executors
                .newFixedThreadPool(parallelism) { task ->
                    Thread(task, "pg-persistence-transaction").apply { isDaemon = true }
                }.asCoroutineDispatcher()

        /** Close after active transactions have finished, during application shutdown. */
        final override fun close() = ownerDispatcher.close()

        /** Capture context on entry and again inside the active transaction callback. */
        protected open fun invocationContext(): CoroutineContext = EmptyCoroutineContext

        protected abstract fun <T> executeBlocking(
            headers: Map<String, String>,
            block: DbTransactionScope.() -> T,
        ): DbTransactionCommit<T>

        final override suspend fun <T> execute(
            headers: Map<String, String>,
            block: suspend DbTransactionScope.() -> T,
        ): DbTransactionCommit<T> {
            val callerContext = currentCoroutineContext()
            val transactionContext = invocationContext()
            return permits
                .withPermit {
                    withContext(transactionContext + ownerDispatcher) {
                        // Keep ordinary cleanup failures attached when crossing the dispatcher boundary.
                        captureException {
                            executeBlocking(headers) {
                                val scope = this
                                val callbackContext =
                                    callerContext
                                        .minusKey(RequestParentJobContextElement) + invocationContext()
                                runBlocking(callbackContext) { block(scope) }
                            }
                        }
                    }
                }.getOrThrow()
        }
    }

internal typealias MutationWriter = ((String) -> PreparedMutation) -> DbTransactionOperation

/**
 * Executes mutations immediately while an external transaction implementation owns commit/rollback.
 * The caller must commit only after this returns and any required result storage succeeds.
 */
fun <T> executeImmediateTransaction(
    execute: (PgGraphqlRequest) -> DbResult<JsonObject>,
    block: DbTransactionScope.() -> T,
): DbTransactionCommit<T> = ImmediateTransaction(execute).run(block)

private class ImmediateTransaction(
    private val execute: (PgGraphqlRequest) -> DbResult<JsonObject>,
) {
    private val transactionId = UUID.randomUUID().toString()
    private val lock = Any()
    private val payloads = linkedMapOf<DbTransactionOperation, JsonObject>()
    private var active = true
    private var failure: Exception? = null
    private var operationFailed = false

    fun <T> run(block: DbTransactionScope.() -> T): DbTransactionCommit<T> =
        try {
            val scope =
                DbTransactionScope(
                    write = { factory -> add(factory).first },
                    execute = { factory -> add(factory).second },
                    executeRequest = ::request,
                )
            val value = scope.block()
            synchronized(lock) {
                failure?.let { throw it }
                check(!operationFailed) { "Transaction operation failed" }
                require(payloads.isNotEmpty()) { "Cannot commit a transaction with no operations" }
                DbTransactionCommit(value, DbTransactionResult(payloads))
            }
        } finally {
            synchronized(lock) { active = false }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun add(factory: (String) -> PreparedMutation): Pair<DbTransactionOperation, JsonObject> =
        synchronized(lock) {
            check(active) { "Transaction scope is closed" }
            failure?.let { throw it }
            check(!operationFailed) { "Transaction operation failed" }
            var completed = false
            try {
                val alias = "operation${payloads.size}"
                val query = PreparedTransaction(listOf(factory(alias))).query
                val request = PgGraphqlRequest(query.text, query.variables.jsonObject, "DbTransaction")
                val data = request(request).strict("transaction")
                require(data.keys == setOf(alias)) { "Mutation response has unexpected operation aliases" }
                val payload = data.getValue(alias).jsonObject
                validateTransactionPayload(payload)
                val operation = DbTransactionOperation(alias, transactionId)
                payloads[operation] = payload
                completed = true
                operation to payload
            } catch (cause: Exception) {
                failure = cause
                throw cause
            } finally {
                if (!completed) operationFailed = true
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun request(request: PgGraphqlRequest): DbResult<JsonObject> =
        synchronized(lock) {
            check(active) { "Transaction scope is closed" }
            failure?.let { throw it }
            check(!operationFailed) { "Transaction operation failed" }
            var completed = false
            try {
                execute(request).also { completed = true }
            } catch (cause: Exception) {
                failure = cause
                throw cause
            } finally {
                if (!completed) operationFailed = true
            }
        }
}

internal fun validateTransactionPayload(payload: JsonObject) {
    require((payload["affectedCount"]?.jsonPrimitive?.intOrNull ?: -1) >= 0) { "Missing affectedCount" }
    val records = requireNotNull(payload["records"] as? JsonArray) { "Missing records" }
    records.forEach {
        require(it.jsonObject["uuidId"]?.jsonPrimitive?.isString == true) { "Missing uuidId" }
    }
}
