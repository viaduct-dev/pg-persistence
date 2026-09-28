@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db
import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslation
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import dev.viaduct.persistence.runtime.node.NodeListPager
import dev.viaduct.persistence.runtime.node.NodeReferenceHydrator
import dev.viaduct.persistence.runtime.node.NodeReferencePlanner
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import viaduct.api.FieldValue
import viaduct.api.context.ResolverExecutionContext
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
                ) { it },
            )
            val returnedIds =
                requireNotNull(result.data)
                    .getValue("edges")
                    .jsonArray
                    .map { edge ->
                        edge.jsonObject
                            .getValue("node")
                            .jsonObject
                            .getValue("uuidId")
                            .jsonPrimitive.content
                    }.toSet()
            if (returnedIds.isEmpty()) break
            val next = remaining.filterNot(returnedIds::contains)
            check(next.size < remaining.size) { "Db batch '$collectionField' returned none of the remaining IDs" }
            remaining = next
        }
        return rows
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
