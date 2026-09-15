@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db
import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslation
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import dev.viaduct.persistence.runtime.node.NodeListPager
import dev.viaduct.persistence.runtime.node.NodeReferenceHydrator
import dev.viaduct.persistence.runtime.node.NodeReferencePlanner
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import viaduct.api.FieldValue
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Query

/** Hydrates a batch of node references with one filtered pg_graphql request. */
internal class DbBatchFetcher(
    private val transport: PgGraphqlTransport,
    private val queryPlanner: DbQueryPlanner,
    private val typeReflection: GeneratedTypeReflection,
    private val nodeReferencePlanner: NodeReferencePlanner,
    private val nodeReferenceHydrator: NodeReferenceHydrator,
) {
    suspend fun <T> fetchByInternalIds(
        context: ResolverExecutionContext<out Query>,
        collectionField: String,
        ids: List<String>,
        ownedSelections: SelectionSet<T>,
        requestedSelections: SelectionSet<T>,
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
        requestedSelections: SelectionSet<T>,
    ): Map<String, FieldValue<T>> where T : CompositeOutput, T : NodeObject {
        if (ids.isEmpty()) return emptyMap()
        val references = nodeReferencePlanner.plan(requestedSelections, ownedSelections)
        val root =
            DbRoot(
                field = collectionField,
                arguments = "(filter: {uuidId: {in: \$ids}})",
                variableDefinitions = "\$ids: [UUID!]!",
                variables =
                    buildJsonObject {
                        put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
                    },
                singleViaFilteredCollection = true,
            )
        val query =
            queryPlanner.plan(
                root = root,
                selections = ownedSelections,
                referenceSelections = references.map { it.upstreamSelection(typeReflection) } + "uuidId",
            )
        val result = transport.executeResult(context, query)
        return DbBatchResultMapper
            .map(
                requestedIds = ids,
                collectionField = collectionField,
                responseKey = query.responseKey,
                data = result.data,
                errors = result.errors,
            ) { it }
            .mapValues { (id, value) ->
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
