@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db
import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslation
import dev.viaduct.persistence.runtime.connection.PagingAccess
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import dev.viaduct.persistence.runtime.node.NodeListPager
import dev.viaduct.persistence.runtime.node.NodeReferenceHydrator
import dev.viaduct.persistence.runtime.node.NodeReferencePlanner
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import dev.viaduct.persistence.runtime.select.exportFragment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import viaduct.api.FieldValue
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Query

/** Hydrates node references, fetching remaining IDs when pg_graphql limits a response. */
internal class DbBatchFetcher(
    private val transport: PgGraphqlTransport,
    private val queryPlanner: DbQueryPlanner,
    private val typeReflection: GeneratedTypeReflection,
    private val nodeReferencePlanner: NodeReferencePlanner,
    private val nodeReferenceHydrator: NodeReferenceHydrator,
) {
    private val rowValidator = DbRowValidator(typeReflection)

    suspend fun <T, C> fetchByInternalIdsResult(
        contexts: List<C>,
        collectionField: String,
    ): Map<C, FieldValue<T>>
        where T : CompositeOutput,
              T : NodeObject,
              C : SelectiveNodeExecutionContext<T> =
        contexts
            .groupBy { it.ownedNodeSelections().compatibilityKey() to it.requestedNodeSelections().compatibilityKey() }
            .values
            .flatMap { compatibleContexts ->
                val representative = compatibleContexts.first()
                val byId =
                    fetchByInternalIdsResult(
                        representative,
                        collectionField,
                        compatibleContexts.map { it.id.internalID },
                        representative.ownedNodeSelections(),
                        representative.requestedNodeSelections(),
                    )
                compatibleContexts.map { context ->
                    context to byId.getValue(context.id.internalID)
                }
            }.toMap()

    suspend fun <T> fetchByInternalIds(
        context: ResolverExecutionContext<out Query>,
        collectionField: String,
        ids: List<String>,
        ownedSelections: SelectionSet<T>,
        requestedSelections: SelectionSet<T> = ownedSelections,
    ): Map<String, T> where T : CompositeOutput, T : NodeObject =
        fetchByInternalIdsResult(
            context,
            collectionField,
            ids,
            ownedSelections,
            requestedSelections,
        ).mapValues { (_, value) -> value.get() }

    suspend fun <T> fetchByInternalIdsResult(
        context: ResolverExecutionContext<out Query>,
        collectionField: String,
        ids: List<String>,
        ownedSelections: SelectionSet<T>,
        requestedSelections: SelectionSet<T> = ownedSelections,
    ): Map<String, FieldValue<T>> where T : CompositeOutput, T : NodeObject {
        PagingAccess.validateSelections(ownedSelections, typeReflection)
        if (ids.isEmpty()) return emptyMap()
        val references = nodeReferencePlanner.plan(ownedSelections, requestedSelections)
        return fetchRows(
            context,
            collectionField,
            ids,
            ownedSelections,
            references.map { it.upstreamSelection(typeReflection) } + "uuidId",
        ).mapValues { (id, value) ->
            runCatching {
                val response =
                    NodeListPager.complete(value.get(), references) { selection ->
                        loadListPage(context, collectionField, id, ownedSelections, selection)
                    }
                FieldValue.ofValue(
                    nodeReferenceHydrator.hydrate(
                        base = response,
                        selections = ownedSelections,
                        references = references,
                        context = context,
                    ),
                )
            }.getOrElse { failure ->
                if (failure is kotlinx.coroutines.CancellationException || failure !is Exception) throw failure
                FieldValue.ofError(failure)
            }
        }
    }

    private suspend fun fetchRows(
        context: ResolverExecutionContext<out Query>,
        collectionField: String,
        ids: List<String>,
        selections: SelectionSet<*>,
        referenceSelections: List<String>,
    ): Map<String, FieldValue<JsonObject>> {
        var remaining = ids.distinct()
        val rows = linkedMapOf<String, FieldValue<JsonObject>>()
        while (remaining.isNotEmpty()) {
            val query = queryPlanner.plan(batchRoot(collectionField, remaining), selections, referenceSelections)
            val result = transport.executeResult(context, query)
            // Map each response separately: GraphQL edge-error indexes start at zero on every request.
            rows.putAll(
                DbBatchResultMapper.map(
                    requestedIds = remaining,
                    collectionField = collectionField,
                    responseKey = query.responseKey,
                    data = result.data,
                    errors = result.errors,
                    validate = { rowValidator.validate(it, emptyList(), selections, query.responseKey) },
                ) { it },
            )
            val edges = result.data?.get("edges") as? JsonArray ?: error("Missing batch edges")
            val returnedIds = DbBatchRows(edges).returnedIds
            if (returnedIds.size < edges.size) {
                // Null nodes have no identity. Recover one ID per request; provider order is not a contract.
                remaining.filterNot(returnedIds::contains).forEach { id ->
                    rows[id] = recoverRow(context, collectionField, id, selections, referenceSelections)
                }
                return rows
            }
            if (returnedIds.isEmpty()) break
            val next = remaining.filterNot(returnedIds::contains)
            check(next.size < remaining.size) { "Db batch '$collectionField' returned none of the remaining IDs" }
            remaining = next
        }
        return rows
    }

    private suspend fun recoverRow(
        context: ResolverExecutionContext<out Query>,
        collectionField: String,
        id: String,
        selections: SelectionSet<*>,
        referenceSelections: List<String>,
    ): FieldValue<JsonObject> =
        runCatching {
            val query = queryPlanner.plan(batchRoot(collectionField, listOf(id)), selections, referenceSelections)
            val result = transport.executeResult(context, query)
            DbBatchResultMapper
                .map(
                    requestedIds = listOf(id),
                    collectionField = collectionField,
                    responseKey = query.responseKey,
                    data = result.data,
                    errors = result.errors,
                    validate = { rowValidator.validate(it, emptyList(), selections, query.responseKey) },
                ) { it }
                .getValue(id)
        }.getOrElse { failure ->
            if (failure is kotlinx.coroutines.CancellationException || failure !is Exception) throw failure
            FieldValue.ofError(failure)
        }

    private fun batchRoot(
        collectionField: String,
        ids: List<String>,
    ) = DbRoot(
        field = collectionField,
        arguments = "(filter: {uuidId: {in: \$ids}}, first: \$first)",
        variableDefinitions = "\$ids: [UUID!]!, \$first: Int!",
        variables =
            buildJsonObject {
                put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
                put("first", ids.size)
            },
        singleViaFilteredCollection = true,
    )

    private suspend fun loadListPage(
        context: ResolverExecutionContext<out Query>,
        collectionField: String,
        id: String,
        selections: SelectionSet<*>,
        selection: String,
    ): kotlinx.serialization.json.JsonObject {
        val root =
            DbRoot(
                field = collectionField,
                singleViaFilteredCollection = true,
                arguments = "(filter: {uuidId: {eq: \$id}})",
                variableDefinitions = "\$id: UUID!",
                variables = buildJsonObject { put("id", id) },
            )
        val page =
            checkNotNull(transport.executeResult(context, queryPlanner.plan(root, selections, listOf(selection))))
                .strict(root.responseKey)
        return DbResponseReader.firstNodeOrNull(PgGraphqlTranslation.restoreViaductResponseShape(page).jsonObject)
            ?: error("Db parent '$id' disappeared while paging its lists")
    }
}

private data class SelectionCompatibilityKey(
    val document: String,
    val variables: Map<String, Any?>,
)

private fun SelectionSet<*>.compatibilityKey(): SelectionCompatibilityKey =
    exportFragment().let { SelectionCompatibilityKey(it.document, it.variables) }
