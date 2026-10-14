@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.connection.ConnectionFetcher
import dev.viaduct.persistence.runtime.graphql.HttpPgGraphqlExecutor
import dev.viaduct.persistence.runtime.graphql.PgGraphqlExecutor
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import dev.viaduct.persistence.runtime.node.NodeReferenceHydrator
import dev.viaduct.persistence.runtime.node.NodeReferencePlanner
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import io.ktor.client.HttpClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import viaduct.api.FieldValue
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.context.ExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.context.SelectiveFieldExecutionContext
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.reflect.CompositeField
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Connection
import viaduct.api.types.NodeObject
import viaduct.api.types.Query

/**
 * Supplies provider-specific headers for each db request.
 *
 * The callback is evaluated for every request so applications can derive authorization from the
 * current execution context instead of storing request credentials in the runtime client.
 */
fun interface DbRequestHeaders {
    suspend fun forContext(context: ExecutionContext): Map<String, String>
}

/**
 * Sends pg_graphql requests for the fields in a Viaduct selection set.
 *
 * The client uses reflection metadata generated with the GRTs to match Viaduct fields to
 * pg_graphql fields. Applications supply the endpoint, HTTP client, and request headers.
 */
@Suppress("TooManyFunctions")
class DbClient(
    executor: PgGraphqlExecutor,
    private val requestHeaders: DbRequestHeaders =
        DbRequestHeaders { emptyMap() },
    retryableTransactions: DbRetryableTransactions? = null,
    private val transactions: DbTransactions? = null,
) {
    init {
        require(transactions == null || retryableTransactions == null) {
            "Choose transactions or HTTP retryableTransactions, not both"
        }
    }

    constructor(
        httpClient: HttpClient,
        endpoint: String,
        requestHeaders: DbRequestHeaders = DbRequestHeaders { emptyMap() },
        retryableTransactions: DbRetryableTransactions? = null,
        transactions: DbTransactions? = null,
    ) : this(HttpPgGraphqlExecutor(httpClient, endpoint), requestHeaders, retryableTransactions, transactions)

    private val typeReflection = GeneratedTypeReflection()
    private val transport =
        PgGraphqlTransport(
            executor = executor,
            requestHeaders = requestHeaders,
        )
    private val queryPlanner = DbQueryPlanner(typeReflection)
    private val nodeReferencePlanner = NodeReferencePlanner(typeReflection)
    private val nodeReferenceHydrator = NodeReferenceHydrator(typeReflection)
    private val dbFetcher =
        DbFetcher(
            transport = transport,
            queryPlanner = queryPlanner,
            typeReflection = typeReflection,
            nodeReferencePlanner = nodeReferencePlanner,
            nodeReferenceHydrator = nodeReferenceHydrator,
        )
    private val dbBatchFetcher =
        DbBatchFetcher(
            transport = transport,
            queryPlanner = queryPlanner,
            typeReflection = typeReflection,
            nodeReferencePlanner = nodeReferencePlanner,
            nodeReferenceHydrator = nodeReferenceHydrator,
        )
    private val connectionFetcher = ConnectionFetcher(transport, typeReflection)
    private val lookupFetcher = LookupFetcher(transport, typeReflection, connectionFetcher)
    private val mutationClient = PgGraphqlMutationClient(executor)
    private val retryExecutor =
        retryableTransactions?.let { RetryableTransactionExecutor(transport, requestHeaders, it) }

    /** Selects the persisted node type for payload-producing mutation operations. */
    @Suppress("MaxLineLength")
    inline fun <reified T : NodeObject> entity(): DbEntityMutations<T> = DbEntityMutations(this, reflectedType(T::class.java))

    /** Returns every matching row as a Viaduct node reference, preserving duplicate projections. */
    suspend fun <K, N : NodeObject> lookup(
        ctx: ResolverExecutionContext<out Query>,
        lookup: DbLookup<K, N>,
        key: K,
        orderBy: List<PgGraphqlOrder> = emptyList(),
    ): List<N> = lookupFetcher.fetch(ctx, lookup, key, orderBy)

    /** Pages matching source rows through the generated modern Viaduct connection. */
    suspend fun <K, N : NodeObject, R : Connection<*, *>, C> lookupConnection(
        ctx: C,
        lookup: DbLookup<K, N>,
        key: K,
        orderBy: List<PgGraphqlOrder> = emptyList(),
    ): R where C : ConnectionFieldExecutionContext<*, *, *, R>, C : SelectiveFieldExecutionContext<R> =
        lookupFetcher.fetchConnection(ctx, lookup, key, ctx.selections(), orderBy)

    /** Explicit selections for callers that already have a connection selection set. */
    suspend fun <K, N : NodeObject, R : Connection<*, *>> lookupConnection(
        ctx: ConnectionFieldExecutionContext<*, *, *, R>,
        lookup: DbLookup<K, N>,
        key: K,
        selections: SelectionSet<R>,
        orderBy: List<PgGraphqlOrder> = emptyList(),
    ): R = lookupFetcher.fetchConnection(ctx, lookup, key, selections, orderBy)

    /** Begins an in-memory transaction that sends its buffered operations together on commit. */
    fun beginTransaction(
        ctx: ExecutionContext,
        operationId: String? = null,
    ): DbTransaction {
        check(transactions == null) {
            "Configured transactions require transaction(ctx) { ... }; standalone beginTransaction is buffered only"
        }
        require(operationId == null || retryExecutor != null) {
            "Configure retryableTransactions before supplying an operationId"
        }
        return DbTransaction(transport, ctx, operationId, retryExecutor)
    }

    /** Executes [block] with the configured transaction implementation, buffering by default. */
    suspend fun <T> transaction(
        ctx: ExecutionContext,
        block: suspend DbTransactionScope.() -> T,
    ): DbTransactionCommit<T> {
        val configured = transactions ?: return beginTransaction(ctx).execute(block)
        return withoutNestedTransaction {
            val headers = requireNotNull(requestHeaders.forContext(ctx))
            currentCoroutineContext().ensureActive()
            configured.execute(headers) {
                val scope = this
                withContext(ActiveImmediateTransaction(this@DbClient, transport, scope)) {
                    block(scope)
                }
            }
        }
    }

    /** Commits with duplicate protection; retries resend the prepared request, not [block]. */
    suspend fun <T> transaction(
        ctx: ExecutionContext,
        operationId: String,
        block: suspend DbTransactionScope.() -> T,
    ): DbTransactionCommit<T> = beginTransaction(ctx, operationId).execute(block)

    /** Null means no committed record was visible, not proof of rollback. Reauthorize before lookup. */
    suspend fun lookupTransaction(
        ctx: ExecutionContext,
        operationId: String,
    ): DbRecoveredTransaction? =
        requireNotNull(retryExecutor) { "Configure retryableTransactions first" }
            .lookup(ctx, operationId)

    /** Resumes an exported request with fresh credentials and the same trusted scope. */
    suspend fun resumeTransaction(
        ctx: ExecutionContext,
        prepared: DbPreparedTransaction,
    ): DbTransactionResult =
        withoutNestedTransaction {
            val executor = requireNotNull(retryExecutor) { "Configure retryableTransactions first" }
            executor.execute(ctx, prepared).strict("transaction")
        }

    internal suspend fun insertRaw(
        ctx: ExecutionContext,
        input: PgGraphqlObject,
        entityName: String,
    ): JsonObject =
        activeImmediateTransaction()?.execute(preparedInsert(PgGraphqlEntity(entityName), listOf(input)))
            ?: mutationClient.insert(
                PgGraphqlEntity(entityName),
                buildJsonArray { add(input.encoded()) },
                selection = "affectedCount records { uuidId }",
                headers = requestHeaders.forContext(ctx),
            )

    internal suspend fun insertRaw(
        ctx: ExecutionContext,
        inputs: Iterable<PgGraphqlObject>,
        entityName: String,
    ): JsonObject =
        activeImmediateTransaction()?.execute(preparedInsert(PgGraphqlEntity(entityName), inputs))
            ?: mutationClient.insert(
                PgGraphqlEntity(entityName),
                buildJsonArray { inputs.forEach { add(it.encoded()) } },
                selection = "affectedCount records { uuidId }",
                headers = requestHeaders.forContext(ctx),
            )

    internal suspend fun updateRaw(
        ctx: ExecutionContext,
        mutation: PgGraphqlUpdate,
        entityName: String,
    ): JsonObject =
        activeImmediateTransaction()?.execute(preparedUpdate(PgGraphqlEntity(entityName), mutation))
            ?: mutationClient.update(
                PgGraphqlEntity(entityName),
                mutation.values.encoded(),
                mutation.filter.encoded(),
                atMost = mutation.atMost,
                selection = "affectedCount records { uuidId }",
                headers = requestHeaders.forContext(ctx),
            )

    internal suspend fun updateRaw(
        ctx: ExecutionContext,
        mutations: Iterable<PgGraphqlUpdate>,
        entityName: String,
    ): JsonObject = mutations.map { updateRaw(ctx, it, entityName) }.combinedMutationPayload()

    internal suspend fun deleteRaw(
        ctx: ExecutionContext,
        mutation: PgGraphqlDelete,
        entityName: String,
    ): JsonObject =
        activeImmediateTransaction()?.execute(preparedDelete(PgGraphqlEntity(entityName), mutation))
            ?: mutationClient.delete(
                PgGraphqlEntity(entityName),
                mutation.filter.encoded(),
                atMost = 1,
                selection = "affectedCount records { uuidId }",
                headers = requestHeaders.forContext(ctx),
            )

    internal suspend fun deleteRaw(
        ctx: ExecutionContext,
        mutations: Iterable<PgGraphqlDelete>,
        entityName: String,
    ): JsonObject = mutations.map { deleteRaw(ctx, it, entityName) }.combinedMutationPayload()

    /** Fetches [selections] and converts the result to a GRT. Shortcut for [fetchJson] + [toGRT]. */
    suspend fun <T : CompositeOutput> fetch(
        ctx: ExecutionContext,
        dbRead: DbRead,
        selections: SelectionSet<T>,
    ): T = dbFetcher.fetch(ctx, dbRead, selections)

    /** Returns a [DbResult] containing a GRT and any GraphQL errors returned by pg_graphql. */
    suspend fun <T : CompositeOutput> fetchResult(
        ctx: ExecutionContext,
        dbRead: DbRead,
        selections: SelectionSet<T>,
    ): DbResult<T> = dbFetcher.fetchResult(ctx, dbRead, selections)

    /**
     * Fetches the raw pg_graphql JSON for [selections], without converting it to a GRT. Use
     * [toGRT] to convert the result, or [fetch] for the common case of doing both in one call.
     */
    suspend fun <T : CompositeOutput> fetchJson(
        ctx: ExecutionContext,
        dbRead: DbRead,
        selections: SelectionSet<T>,
    ): JsonObject = dbFetcher.fetchJson(ctx, dbRead, selections)

    /** Fetches partial JSON data while preserving upstream GraphQL errors. */
    suspend fun <T : CompositeOutput> fetchJsonResult(
        ctx: ExecutionContext,
        dbRead: DbRead,
        selections: SelectionSet<T>,
    ): DbResult<JsonObject> = dbFetcher.fetchJsonResult(ctx, dbRead, selections)

    /** Fetches one node using the owned selections from its selective node context. */
    suspend fun <T> fetchNode(
        ctx: SelectiveNodeExecutionContext<T>,
        dbRead: DbRead,
    ): T where T : CompositeOutput, T : NodeObject =
        dbFetcher.fetchNode(
            ctx,
            dbRead,
            ctx.ownedNodeSelections(),
            ctx.requestedNodeSelections(),
        )

    /** Fetches one node by its provider UUID and returns its generated Viaduct result type. */
    suspend fun <T> fetchByInternalId(
        ctx: SelectiveNodeExecutionContext<T>,
        collectionField: String,
        id: String,
    ): T where T : CompositeOutput, T : NodeObject =
        fetchNode(
            ctx,
            DbRead(
                root =
                    DbRoot(
                        field = collectionField,
                        arguments = "(filter: {uuidId: {eq: \$id}})",
                        variableDefinitions = "\$id: UUID!",
                        variables = buildJsonObject { put("id", id) },
                        singleViaFilteredCollection = true,
                    ),
            ),
        )

    /**
     * Fetches nodes by provider UUID, keyed by UUID; missing rows or node errors throw. Requests
     * additional pages if pg_graphql limits a response.
     */
    suspend fun <T> fetchByInternalIds(
        ctx: SelectiveNodeExecutionContext<T>,
        collectionField: String,
        ids: List<String>,
    ): Map<String, T> where T : CompositeOutput, T : NodeObject =
        dbBatchFetcher.fetchByInternalIds(
            ctx,
            collectionField,
            ids,
            ctx.ownedNodeSelections(),
            ctx.requestedNodeSelections(),
        )

    /**
     * Hydrates references selected inside a non-node resolver. The caller supplies the one
     * effective selection set to fetch; unlike the former API, there is no second requested
     * selection set for the database layer to merge or interpret.
     */
    suspend fun <T> fetchByInternalIds(
        ctx: ResolverExecutionContext<out Query>,
        collectionField: String,
        ids: List<String>,
        ownedSelections: SelectionSet<T>,
    ): Map<String, T> where T : CompositeOutput, T : NodeObject =
        dbBatchFetcher.fetchByInternalIds(ctx, collectionField, ids, ownedSelections)

    /**
     * Fetches nodes by provider UUID, keyed by UUID as independent Viaduct field values. A missing
     * row or an upstream error associated with one returned node becomes an error value for that
     * UUID without discarding the other nodes.
     */
    suspend fun <T> fetchByInternalIdsResult(
        ctx: SelectiveNodeExecutionContext<T>,
        collectionField: String,
        ids: List<String>,
    ): Map<String, FieldValue<T>> where T : CompositeOutput, T : NodeObject =
        dbBatchFetcher.fetchByInternalIdsResult(
            ctx,
            collectionField,
            ids,
            ctx.ownedNodeSelections(),
            ctx.requestedNodeSelections(),
        )

    /**
     * Fetches every selective node context with its own owned selections. Contexts with compatible
     * selections and field arguments share a pg_graphql request; incompatible contexts are fetched
     * separately and mapped back to their original resolver contexts.
     */
    suspend fun <T, C> fetchByInternalIdsResult(
        contexts: List<C>,
        collectionField: String,
    ): Map<C, FieldValue<T>>
        where T : CompositeOutput,
              T : NodeObject,
              C : SelectiveNodeExecutionContext<T> =
        dbBatchFetcher.fetchByInternalIdsResult(contexts, collectionField)

    /**
     * Reads a modern Viaduct connection using its generated arguments and connection builder.
     * For a nested field, supply its reflection descriptor and a filtered single-parent read.
     * The root read identifies the parent; filter and orderBy apply to the connection.
     * Paging comes exclusively from ctx.arguments.
     */
    @Suppress("LongParameterList") // Keep provider filters separate from Viaduct paging arguments.
    suspend fun <R : Connection<*, *>> fetchConnection(
        ctx: ConnectionFieldExecutionContext<*, *, *, R>,
        dbRead: DbRead,
        selections: SelectionSet<R>,
        field: CompositeField<*, *>? = null,
        filter: PgGraphqlFilter = PgGraphqlFilter.empty(),
        orderBy: List<PgGraphqlOrder> = emptyList(),
    ): R = connectionFetcher.fetch(ctx, dbRead, selections, field, filter, orderBy)
}

private suspend fun DbClient.activeImmediateTransaction(): DbTransactionScope? =
    currentCoroutineContext()[ActiveImmediateTransaction]
        ?.takeIf { it.owner === this }
        ?.scope

private fun List<JsonObject>.combinedMutationPayload(): JsonObject =
    buildJsonObject {
        put("affectedCount", sumOf { it["affectedCount"]?.jsonPrimitive?.int ?: 0 })
        put(
            "records",
            JsonArray(flatMap { it["records"]?.jsonArray?.toList().orEmpty() }),
        )
    }
