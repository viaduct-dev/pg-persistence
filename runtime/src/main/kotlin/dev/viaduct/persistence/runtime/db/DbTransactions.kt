package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.graphql.PgGraphqlRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Optional execution of the existing transaction lambda, selected when constructing [DbClient]. */
interface DbTransactions {
    /** Context required while invoking this implementation, captured on the caller's thread. */
    fun coroutineContext(): CoroutineContext = EmptyCoroutineContext

    fun <T> execute(
        headers: Map<String, String>,
        block: DbTransactionScope.() -> T,
    ): DbTransactionCommit<T>
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
    private var failure: Throwable? = null

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
                operation to payload
            } catch (cause: Throwable) {
                failure = cause
                throw cause
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun request(request: PgGraphqlRequest): DbResult<JsonObject> =
        synchronized(lock) {
            check(active) { "Transaction scope is closed" }
            failure?.let { throw it }
            try {
                execute(request)
            } catch (cause: Throwable) {
                failure = cause
                throw cause
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
