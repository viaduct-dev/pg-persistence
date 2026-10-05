@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db
import dev.viaduct.persistence.runtime.connection.ConnectionFetcher
import dev.viaduct.persistence.runtime.connection.ConnectionPageRequest
import dev.viaduct.persistence.runtime.connection.NestedConnectionPageRequest
import dev.viaduct.persistence.runtime.connection.UuidConnectionPage
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import dev.viaduct.persistence.runtime.node.NodeReferenceHydrator
import dev.viaduct.persistence.runtime.node.NodeReferencePlanner
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import viaduct.api.FieldValue
import viaduct.api.context.ExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
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
    private val httpClient: HttpClient,
    private val endpoint: String,
    private val requestHeaders: DbRequestHeaders =
        DbRequestHeaders { emptyMap() },
) {
    private val typeReflection = GeneratedTypeReflection()
    private val transport =
        PgGraphqlTransport(
            httpClient = httpClient,
            endpoint = endpoint,
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
    private val connectionFetcher = ConnectionFetcher(transport)
    private val mutationClient = PgGraphqlMutationClient(httpClient, endpoint)

    /** Selects the persisted node type for payload-producing mutation operations. */
    @Suppress("MaxLineLength")
    inline fun <reified T : NodeObject> entity(): DbEntityMutations<T> = DbEntityMutations(this, reflectedType(T::class.java))

    /** Begins an in-memory transaction that sends its buffered operations together on commit. */
    fun beginTransaction(ctx: ExecutionContext): DbTransaction = DbTransaction(transport, ctx)

    /** Buffers mutations in [block] and commits them together after the block succeeds. */
    suspend fun <T> transaction(
        ctx: ExecutionContext,
        block: DbTransactionScope.() -> T,
    ): DbTransactionCommit<T> = beginTransaction(ctx).execute(block)

    internal suspend fun insertRaw(
        ctx: ExecutionContext,
        input: PgGraphqlObject,
        entityName: String,
    ): JsonObject =
        mutationClient.insert(
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
        mutationClient.insert(
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
        mutationClient.update(
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
        mutationClient.delete(
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

    suspend fun <T> fetchNode(
        ctx: ResolverExecutionContext<out Query>,
        dbRead: DbRead,
        ownedSelections: SelectionSet<T>,
        requestedSelections: SelectionSet<T>,
    ): T where T : CompositeOutput, T : NodeObject =
        dbFetcher.fetchNode(
            ctx,
            dbRead,
            ownedSelections,
            requestedSelections,
        )

    suspend fun <T> fetchByInternalId(
        ctx: ResolverExecutionContext<out Query>,
        collectionField: String,
        id: String,
        ownedSelections: SelectionSet<T>,
        requestedSelections: SelectionSet<T>,
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
            ownedSelections,
            requestedSelections,
        )

    /**
     * Fetches and hydrates several nodes with one pg_graphql request. The returned map uses the
     * provider UUID, so callers can put the objects back into the connection's original order.
     */
    suspend fun <T> fetchByInternalIds(
        ctx: ResolverExecutionContext<out Query>,
        collectionField: String,
        ids: List<String>,
        ownedSelections: SelectionSet<T>,
        requestedSelections: SelectionSet<T> = ownedSelections,
    ): Map<String, T> where T : CompositeOutput, T : NodeObject =
        dbBatchFetcher.fetchByInternalIds(
            ctx,
            collectionField,
            ids,
            ownedSelections,
            requestedSelections,
        )

    /**
     * Fetches several nodes as independent Viaduct field values. A missing row or an upstream
     * error associated with one returned node becomes an error value for that UUID without
     * discarding the other nodes.
     */
    suspend fun <T> fetchByInternalIdsResult(
        ctx: ResolverExecutionContext<out Query>,
        collectionField: String,
        ids: List<String>,
        ownedSelections: SelectionSet<T>,
        requestedSelections: SelectionSet<T> = ownedSelections,
    ): Map<String, FieldValue<T>> where T : CompositeOutput, T : NodeObject =
        dbBatchFetcher.fetchByInternalIdsResult(
            ctx,
            collectionField,
            ids,
            ownedSelections,
            requestedSelections,
        )

    suspend fun fetchUuidIds(
        ctx: ExecutionContext,
        collectionField: String,
        arguments: String = "",
        variableDefinitions: String = "",
        variables: JsonObject = buildJsonObject {},
    ): List<String> =
        connectionFetcher.fetchUuidIds(
            ctx,
            collectionField,
            arguments,
            variableDefinitions,
            variables,
        )

    /**
     * Fetches a pg_graphql connection while preserving the provider's cursors and page info.
     *
     * Callers that expose a Viaduct connection should pass these cursors back to this method on
     * the next request. This keeps pagination database-managed instead of converting a cursor
     * into an offset or loading the entire collection into the application.
     */
    suspend fun fetchUuidConnection(
        ctx: ExecutionContext,
        request: ConnectionPageRequest,
    ): UuidConnectionPage = connectionFetcher.fetchUuidConnection(ctx, request)

    /** Compatibility overload for callers that pass pagination arguments individually. */
    @Suppress("LongParameterList")
    suspend fun fetchUuidConnection(
        ctx: ExecutionContext,
        collectionField: String,
        first: Int? = null,
        after: String? = null,
        last: Int? = null,
        before: String? = null,
        additionalArguments: String = "",
        additionalVariableDefinitions: String = "",
        additionalVariables: JsonObject = buildJsonObject {},
    ): UuidConnectionPage =
        fetchUuidConnection(
            ctx = ctx,
            request =
                ConnectionPageRequest(
                    collectionField,
                    first,
                    after,
                    last,
                    before,
                    additionalArguments,
                    additionalVariableDefinitions,
                    additionalVariables,
                ),
        )

    /**
     * Loads one paginated child connection for every requested parent in a single pg_graphql
     * query. pg_graphql evaluates the nested connection per parent, so `first`/`after` retain
     * their per-parent meaning without issuing one request per parent.
     */
    suspend fun fetchNestedUuidConnections(
        ctx: ExecutionContext,
        request: NestedConnectionPageRequest,
    ): Map<String, UuidConnectionPage> = connectionFetcher.fetchNestedUuidConnections(ctx, request)

    /** Compatibility overload for callers that pass nested pagination arguments individually. */
    @Suppress("LongParameterList")
    suspend fun fetchNestedUuidConnections(
        ctx: ExecutionContext,
        parentCollectionField: String,
        parentIds: List<String>,
        childCollectionField: String,
        first: Int? = null,
        after: String? = null,
        last: Int? = null,
        before: String? = null,
    ): Map<String, UuidConnectionPage> =
        fetchNestedUuidConnections(
            ctx = ctx,
            request =
                NestedConnectionPageRequest(
                    parentCollectionField = parentCollectionField,
                    parentIds = parentIds,
                    child =
                        ConnectionPageRequest(
                            collectionField = childCollectionField,
                            first = first,
                            after = after,
                            last = last,
                            before = before,
                        ),
                ),
        )
}

private fun List<JsonObject>.combinedMutationPayload(): JsonObject =
    buildJsonObject {
        put("affectedCount", sumOf { it["affectedCount"]?.jsonPrimitive?.int ?: 0 })
        put(
            "records",
            JsonArray(flatMap { it["records"]?.jsonArray?.toList().orEmpty() }),
        )
    }
