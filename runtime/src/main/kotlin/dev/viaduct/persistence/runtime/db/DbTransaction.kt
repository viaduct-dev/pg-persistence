@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.graphql.GraphqlQuery
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import viaduct.api.context.ExecutionContext
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import java.util.UUID

/** Identifies the result of one operation after a transaction commits. */
@ConsistentCopyVisibility
data class DbTransactionOperation
    @java.beans.ConstructorProperties("alias", "transactionId")
    internal constructor(
        @get:JvmName("getAlias")
        internal val alias: String,
        @get:JvmName("getTransactionId")
        internal val transactionId: String,
    )

/** Payloads returned for the operations in a committed transaction. */
class DbTransactionResult internal constructor(
    payloads: Map<DbTransactionOperation, JsonObject>,
) {
    private val payloads = HashMap(payloads)

    operator fun get(operation: DbTransactionOperation): JsonObject? = payloads[operation]

    /** JSON storage for transaction implementations; handles retain their original aliases. */
    fun encode(): String =
        buildJsonObject {
            put("transactionId", payloads.keys.first().transactionId)
            put("payloads", JsonObject(payloads.mapKeys { it.key.alias }))
        }.toString()

    companion object {
        @JvmStatic
        fun decode(encoded: String): DbTransactionResult {
            val value =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(encoded)
                    .jsonObject
            val transactionId = value.getValue("transactionId").jsonPrimitive.content
            return DbTransactionResult(
                value
                    .getValue("payloads")
                    .jsonObject
                    .map { (alias, payload) ->
                        DbTransactionOperation(alias, transactionId) to payload.jsonObject
                    }.toMap(),
            )
        }
    }
}

/** The value returned by a transaction block and the database results produced by its commit. */
data class DbTransactionCommit<T>(
    val value: T,
    val result: DbTransactionResult,
)

/** Mutation operations available inside [DbClient.transaction]. */
class DbTransactionScope internal constructor(
    private val write: MutationWriter,
) {
    internal constructor(transaction: DbTransaction) : this(transaction::add)

    internal fun add(factory: (String) -> PreparedMutation): DbTransactionOperation = write(factory)

    /** Inserts into a generated association table without requiring an application GRT. */
    fun insert(
        entity: PgGraphqlEntity,
        value: PgGraphqlObject,
    ): DbTransactionOperation = add(preparedInsert(entity, listOf(value)))

    fun update(
        entity: PgGraphqlEntity,
        value: PgGraphqlUpdate,
    ): DbTransactionOperation = add(preparedUpdate(entity, value))

    fun delete(
        entity: PgGraphqlEntity,
        value: PgGraphqlDelete,
    ): DbTransactionOperation = add(preparedDelete(entity, value))

    /** Selects the persisted node type for a transaction mutation. */
    @Suppress("MaxLineLength")
    inline fun <reified T : NodeObject> entity(): DbTransactionEntity<T> = entity(T::class.java)

    @PublishedApi
    @Suppress("MaxLineLength")
    internal fun <T : NodeObject> entity(type: Class<T>): DbTransactionEntity<T> = DbTransactionEntity(this, reflectedType(type))
}

/** Buffers pg_graphql mutations and sends them as one GraphQL request when committed. */
class DbTransaction internal constructor(
    private val transport: PgGraphqlTransport,
    private val context: ExecutionContext,
    private val operationId: String? = null,
    private val retryExecutor: RetryableTransactionExecutor? = null,
) {
    private val lock = Any()
    private val transactionId = UUID.randomUUID().toString()
    private val operations = mutableListOf<PreparedMutation>()
    private var status = TransactionStatus.OPEN
    private var frozen: PreparedTransaction? = null
    private var identified: DbPreparedTransaction? = null

    /** Freezes an identified transaction for durable storage before sending it. No request is sent. */
    suspend fun prepare(): DbPreparedTransaction {
        val id = requireNotNull(operationId) { "Preparing for recovery requires an operationId" }
        val prepared =
            synchronized(lock) {
                check(status == TransactionStatus.OPEN || status == TransactionStatus.PREPARED) {
                    "Transaction is $status"
                }
                require(operations.isNotEmpty()) { "Cannot prepare an empty transaction" }
                status = TransactionStatus.PREPARED
                frozen ?: PreparedTransaction(operations.toList(), transactionId).also { frozen = it }
            }
        val existing = synchronized(lock) { identified }
        if (existing != null) return existing
        val identity = requireNotNull(retryExecutor).prepare(context, id, prepared)
        return synchronized(lock) {
            check(status == TransactionStatus.PREPARED) { "Transaction is $status" }
            identified ?: identity.also { identified = it }
        }
    }

    fun insert(
        entity: PgGraphqlEntity,
        value: PgGraphqlObject,
    ): DbTransactionOperation = add(preparedInsert(entity, listOf(value)))

    fun update(
        entity: PgGraphqlEntity,
        value: PgGraphqlUpdate,
    ): DbTransactionOperation = add(preparedUpdate(entity, value))

    fun delete(
        entity: PgGraphqlEntity,
        value: PgGraphqlDelete,
    ): DbTransactionOperation = add(preparedDelete(entity, value))

    /** Selects the persisted node type for a buffered mutation. */
    @Suppress("MaxLineLength")
    inline fun <reified T : NodeObject> entity(): DbTransactionEntity<T> = DbTransactionEntity(this, reflectedType(T::class.java))

    @Suppress("TooGenericExceptionCaught")
    internal suspend fun <T> execute(block: DbTransactionScope.() -> T): DbTransactionCommit<T> =
        try {
            val value = DbTransactionScope(this).block()
            DbTransactionCommit(value, commit())
        } catch (failure: Throwable) {
            abort()
            throw failure
        }

    /** Discards all buffered operations without sending a request. */
    fun abort() {
        synchronized(lock) {
            when (status) {
                TransactionStatus.OPEN,
                TransactionStatus.PREPARED,
                -> {
                    frozen = null
                    identified = null
                    operations.clear()
                    status = TransactionStatus.ABORTED
                }
                TransactionStatus.ABORTED,
                TransactionStatus.FAILED,
                -> Unit
                TransactionStatus.COMMITTING,
                TransactionStatus.COMMITTED,
                -> error("Cannot abort a $status transaction")
            }
        }
    }

    /** Sends all buffered operations in one request and throws if pg_graphql returns errors. */
    suspend fun commit(): DbTransactionResult = commitResult().strict("transaction")

    /** Sends all buffered operations while preserving pg_graphql data and errors. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun commitResult(): DbResult<DbTransactionResult> {
        val prepared =
            synchronized(lock) {
                check(status == TransactionStatus.OPEN || status == TransactionStatus.PREPARED) {
                    "Transaction is $status"
                }
                require(operations.isNotEmpty()) { "Cannot commit a transaction with no operations" }
                status = TransactionStatus.COMMITTING
                frozen ?: PreparedTransaction(operations.toList(), transactionId)
            }
        return try {
            val result =
                if (operationId != null) {
                    val executor = requireNotNull(retryExecutor)
                    val request = synchronized(lock) { identified } ?: executor.prepare(context, operationId, prepared)
                    executor.execute(context, request)
                } else {
                    val response = transport.executeRootResult(context, prepared.query)
                    DbResult(response.data?.let(prepared::decode), response.errors)
                }
            val decoded = result.data
            synchronized(lock) {
                status =
                    if (result.errors.isEmpty() && decoded != null) {
                        TransactionStatus.COMMITTED
                    } else {
                        TransactionStatus.FAILED
                    }
            }
            DbResult(decoded, result.errors)
        } catch (failure: Throwable) {
            synchronized(lock) { status = TransactionStatus.FAILED }
            throw failure
        }
    }

    internal fun add(factory: (String) -> PreparedMutation): DbTransactionOperation =
        synchronized(lock) {
            check(status == TransactionStatus.OPEN) { "Transaction is $status; expected OPEN" }
            val operation = DbTransactionOperation("operation${operations.size}", transactionId)
            operations += factory(operation.alias)
            operation
        }
}

/** Buffered mutation operations for one persisted node type. */
class DbTransactionEntity<T : NodeObject>
    @PublishedApi
    internal constructor(
        private val transaction: DbTransactionScope,
        private val entityType: Type<T>,
    ) {
        @PublishedApi
        internal constructor(transaction: DbTransaction, entityType: Type<T>) :
            this(DbTransactionScope(transaction), entityType)

        private val entity by lazy { PgGraphqlEntity(entityType.name) }

        fun insert(value: PgGraphqlObject): DbTransactionOperation = insertBatch(listOf(value))

        @Suppress("MaxLineLength")
        fun insertBatch(values: Iterable<PgGraphqlObject>): DbTransactionOperation = transaction.add(preparedInsert(entity, values))

        @Suppress("MaxLineLength")
        fun update(mutation: PgGraphqlUpdate): DbTransactionOperation = transaction.add(preparedUpdate(entity, mutation))

        fun updateBatch(mutations: Iterable<PgGraphqlUpdate>): List<DbTransactionOperation> = mutations.map(::update)

        @Suppress("MaxLineLength")
        fun delete(mutation: PgGraphqlDelete): DbTransactionOperation = transaction.add(preparedDelete(entity, mutation))

        fun deleteBatch(mutations: Iterable<PgGraphqlDelete>): List<DbTransactionOperation> = mutations.map(::delete)
    }

internal class PreparedMutation(
    definitions: List<String>,
    val field: String,
    variables: Map<String, JsonElement>,
) {
    val definitions: List<String> = java.util.List.copyOf(definitions)
    val variables: Map<String, JsonElement> = java.util.Collections.unmodifiableMap(LinkedHashMap(variables))
}

internal class PreparedTransaction(
    operations: List<PreparedMutation>,
    val transactionId: String = UUID.randomUUID().toString(),
) {
    private val operations = operations.toList()
    val operationCount: Int get() = operations.size
    val query =
        GraphqlQuery(
            text =
                DbTransactionTemplate.transaction(
                    operations.flatMap(PreparedMutation::definitions),
                    operations.map(PreparedMutation::field),
                ),
            variables =
                buildJsonObject {
                    operations.flatMap { it.variables.entries }.forEach { put(it.key, it.value) }
                },
            responseKey = "operation0",
        )

    fun retryRequest(): JsonObject =
        buildJsonObject {
            put("protocolVersion", 1)
            put("document", query.text)
            put("variables", query.variables)
            put("operationName", "DbTransaction")
        }

    fun decode(data: JsonObject): DbTransactionResult =
        DbTransactionResult(
            data
                .mapNotNull { (alias, payload) ->
                    (payload as? JsonObject)?.let { DbTransactionOperation(alias, transactionId) to it }
                }.toMap(),
        )
}

private fun preparedInsert(
    entity: PgGraphqlEntity,
    values: Iterable<PgGraphqlObject>,
): (String) -> PreparedMutation =
    { alias ->
        PreparedMutation(
            definitions = listOf("\$${alias}Objects: [${entity.typeName}InsertInput!]!"),
            field = DbTransactionTemplate.insert(alias, entity.insertField),
            variables = mapOf("${alias}Objects" to JsonArray(values.map(PgGraphqlObject::encoded))),
        )
    }

private fun preparedUpdate(
    entity: PgGraphqlEntity,
    mutation: PgGraphqlUpdate,
): (String) -> PreparedMutation =
    { alias ->
        PreparedMutation(
            definitions =
                listOf(
                    "\$${alias}Set: ${entity.typeName}UpdateInput!",
                    "\$${alias}Filter: ${entity.typeName}Filter!",
                    "\$${alias}AtMost: Int!",
                ),
            field = DbTransactionTemplate.update(alias, entity.updateField),
            variables =
                mapOf(
                    "${alias}Set" to mutation.values.encoded(),
                    "${alias}Filter" to mutation.filter.encoded(),
                    "${alias}AtMost" to mutation.atMost.toPgGraphqlJsonElement(),
                ),
        )
    }

private fun preparedDelete(
    entity: PgGraphqlEntity,
    mutation: PgGraphqlDelete,
): (String) -> PreparedMutation =
    { alias ->
        PreparedMutation(
            definitions =
                listOf(
                    "\$${alias}Filter: ${entity.typeName}Filter!",
                    "\$${alias}AtMost: Int!",
                ),
            field = DbTransactionTemplate.delete(alias, entity.deleteField),
            variables =
                mapOf(
                    "${alias}Filter" to mutation.filter.encoded(),
                    "${alias}AtMost" to 1.toPgGraphqlJsonElement(),
                ),
        )
    }

private enum class TransactionStatus {
    OPEN,
    PREPARED,
    COMMITTING,
    COMMITTED,
    ABORTED,
    FAILED,
}
