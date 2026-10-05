@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.graphql.GraphqlQuery
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import viaduct.api.context.ExecutionContext
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject

/** Identifies the result of one operation after a transaction commits. */
@JvmInline
value class DbTransactionOperation internal constructor(
    internal val alias: String,
)

/** Payloads returned for the operations in a committed transaction. */
class DbTransactionResult internal constructor(
    payloads: Map<DbTransactionOperation, JsonObject>,
) {
    private val payloads = HashMap(payloads)

    operator fun get(operation: DbTransactionOperation): JsonObject? = payloads[operation]
}

/** The value returned by a transaction block and the database results produced by its commit. */
data class DbTransactionCommit<T>(
    val value: T,
    val result: DbTransactionResult,
)

/** Mutation operations available inside [DbClient.transaction]. */
class DbTransactionScope internal constructor(
    private val transaction: DbTransaction,
) {
    /** Selects the persisted node type for a buffered mutation. */
    @Suppress("MaxLineLength")
    inline fun <reified T : NodeObject> entity(): DbTransactionEntity<T> = entity(T::class.java)

    @PublishedApi
    @Suppress("MaxLineLength")
    internal fun <T : NodeObject> entity(type: Class<T>): DbTransactionEntity<T> = DbTransactionEntity(transaction, reflectedType(type))
}

/** Buffers pg_graphql mutations and sends them as one GraphQL request when committed. */
class DbTransaction internal constructor(
    private val transport: PgGraphqlTransport,
    private val context: ExecutionContext,
) {
    private val lock = Any()
    private val operations = mutableListOf<PreparedMutation>()
    private var status = TransactionStatus.OPEN

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
                TransactionStatus.OPEN -> {
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
                checkOpen()
                require(operations.isNotEmpty()) { "Cannot commit a transaction with no operations" }
                status = TransactionStatus.COMMITTING
                PreparedTransaction(operations.toList())
            }
        return try {
            val result = transport.executeRootResult(context, prepared.query)
            val decoded = result.data?.let(prepared::decode)
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
            checkOpen()
            val operation = DbTransactionOperation("operation${operations.size}")
            operations += factory(operation.alias)
            operation
        }

    private fun checkOpen() {
        check(status == TransactionStatus.OPEN) { "Transaction is $status; expected OPEN" }
    }
}

/** Buffered mutation operations for one persisted node type. */
class DbTransactionEntity<T : NodeObject>
    @PublishedApi
    internal constructor(
        private val transaction: DbTransaction,
        private val entityType: Type<T>,
    ) {
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
    val definitions = definitions.toList()
    val variables = variables.toMap()
}

internal class PreparedTransaction(
    operations: List<PreparedMutation>,
) {
    private val operations = operations.toList()
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

    fun decode(data: JsonObject): DbTransactionResult =
        DbTransactionResult(
            data
                .mapNotNull { (alias, payload) ->
                    (payload as? JsonObject)?.let { DbTransactionOperation(alias) to it }
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
    COMMITTING,
    COMMITTED,
    ABORTED,
    FAILED,
}
