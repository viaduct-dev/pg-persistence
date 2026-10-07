@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db
import dev.viaduct.persistence.runtime.connection.PagingAccess
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import dev.viaduct.persistence.runtime.node.NodeListPager
import dev.viaduct.persistence.runtime.node.NodeReferenceHydrator
import dev.viaduct.persistence.runtime.node.NodeReferencePlanner
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import dev.viaduct.persistence.runtime.select.hasNoSelections
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import viaduct.api.context.ExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Query

/** Executes typed db reads and hydrates requested node references. */
internal class DbFetcher(
    private val transport: PgGraphqlTransport,
    private val queryPlanner: DbQueryPlanner,
    private val typeReflection: GeneratedTypeReflection,
    private val nodeReferencePlanner: NodeReferencePlanner,
    private val nodeReferenceHydrator: NodeReferenceHydrator,
) {
    private val rowValidator = DbRowValidator(typeReflection)

    suspend fun <T : CompositeOutput> fetch(
        context: ExecutionContext,
        dbRead: DbRead,
        selections: SelectionSet<T>,
    ): T = fetchResult(context, dbRead, selections).strict(dbRead.root.responseKey)

    suspend fun <T : CompositeOutput> fetchResult(
        context: ExecutionContext,
        dbRead: DbRead,
        selections: SelectionSet<T>,
    ): DbResult<T> {
        val result = fetchJsonResult(context, dbRead, selections)
        return DbResult(result.data?.toGRT(context, selections), result.errors)
    }

    /** Fetches the raw pg_graphql JSON for [selections], without converting it to a GRT. */
    suspend fun <T : CompositeOutput> fetchJson(
        context: ExecutionContext,
        dbRead: DbRead,
        selections: SelectionSet<T>,
    ): JsonObject = fetchJsonResult(context, dbRead, selections).strict(dbRead.root.responseKey)

    suspend fun <T : CompositeOutput> fetchJsonResult(
        context: ExecutionContext,
        dbRead: DbRead,
        selections: SelectionSet<T>,
        referenceSelections: List<String> = emptyList(),
    ): DbResult<JsonObject> {
        PagingAccess.validateRoot(dbRead.root)
        PagingAccess.validateSelections(selections, typeReflection)
        if (selections.hasNoSelections() && referenceSelections.isEmpty() && !selections.type.kcls.java.isInterface) {
            return DbResult(buildJsonObject { put("__typename", selections.type.name) })
        }
        val query = queryPlanner.plan(dbRead.root, selections, referenceSelections, dbRead.concreteType)
        val result = DbResponseReader.restoreResult(transport.executeResult(context, query), dbRead.root)
        val errors =
            result.data?.let {
                rowValidator.validate(it, result.errors, selections, query.responseKey)
            } ?: result.errors
        return DbResult(result.data, errors)
    }

    suspend fun <T> fetchNode(
        context: ResolverExecutionContext<out Query>,
        dbRead: DbRead,
        ownedSelections: SelectionSet<T>,
        requestedSelections: SelectionSet<T> = ownedSelections,
    ): T where T : CompositeOutput, T : NodeObject {
        val references = nodeReferencePlanner.plan(ownedSelections, requestedSelections)
        if (references.isEmpty()) return fetch(context, dbRead, ownedSelections)

        val response =
            fetchJsonResult(
                context = context,
                dbRead = dbRead,
                selections = ownedSelections,
                referenceSelections = references.map { it.upstreamSelection(typeReflection) },
            ).strict(dbRead.root.responseKey)
        return nodeReferenceHydrator.hydrate(
            base =
                NodeListPager.complete(response, references) { selection ->
                    fetchJsonResult(context, dbRead, ownedSelections, listOf(selection)).strict(dbRead.root.responseKey)
                },
            selections = ownedSelections,
            references = references,
            context = context,
        )
    }
}
