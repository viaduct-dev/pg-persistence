@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db
import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslation
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import dev.viaduct.persistence.runtime.node.NodeListPager
import dev.viaduct.persistence.runtime.node.NodeReferenceHydrator
import dev.viaduct.persistence.runtime.node.NodeReferencePlanner
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import viaduct.api.context.ExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import java.util.WeakHashMap

/** Executes typed db reads and hydrates requested node references. */
internal class DbFetcher(
    private val transport: PgGraphqlTransport,
    private val queryPlanner: DbQueryPlanner,
    private val typeReflection: GeneratedTypeReflection,
    private val nodeReferencePlanner: NodeReferencePlanner,
    private val nodeReferenceHydrator: NodeReferenceHydrator,
) {
    private val semanticValidators = WeakHashMap<ClassLoader, SemanticNotNullValidator>()

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
        if (selections.isEmpty() && referenceSelections.isEmpty() && !selections.type.kcls.java.isInterface) {
            return DbResult(buildJsonObject { put("__typename", selections.type.name) })
        }
        val query = queryPlanner.plan(dbRead.root, selections, referenceSelections, dbRead.concreteType)
        val translationSchema = typeReflection.translationSchema(selections.type)
        val result = transport.executeResult(context, query)
        val restoredEnvelope =
            result.data?.let {
                PgGraphqlTranslation.restoreViaductResponseShape(it).jsonObject
            }
        val restoredErrors =
            result.errors.map { error -> restoreErrorPath(error, query.responseKey) }
        val data =
            if (restoredEnvelope != null && dbRead.root.singleViaFilteredCollection) {
                DbResponseReader.firstNodeOrNull(restoredEnvelope)
            } else {
                restoredEnvelope
            }
        val normalizedErrors =
            if (dbRead.root.singleViaFilteredCollection) {
                restoredErrors.map { it.copy(path = DbResponseReader.unwrapFirstNodePath(it.path)) }
            } else {
                restoredErrors
            }
        val errors =
            data?.let {
                semanticValidator(selections.type.kcls.java.classLoader).validate(
                    SemanticValidationRequest(
                        data = it,
                        errors = normalizedErrors,
                        document = selections.toFragment().document,
                        rootType = selections.type.name,
                        rootResponseKey = query.responseKey,
                        schema = translationSchema,
                    ),
                )
            } ?: normalizedErrors
        return DbResult(data, errors)
    }

    private fun restoreErrorPath(
        error: UpstreamGraphqlError,
        responseKey: String,
    ): UpstreamGraphqlError {
        if (error.path.isEmpty()) return error
        val root = kotlinx.serialization.json.JsonPrimitive(responseKey)
        val hasRoot = error.path.first() == root
        val rawPath = if (hasRoot) error.path.drop(1) else error.path
        val restoredPath = PgGraphqlTranslation.restoreViaductResponsePath(rawPath)
        return error.copy(path = if (hasRoot) listOf(root) + restoredPath else restoredPath)
    }

    private fun semanticValidator(classLoader: ClassLoader): SemanticNotNullValidator =
        synchronized(semanticValidators) {
            semanticValidators.getOrPut(classLoader) {
                SemanticNotNullValidator(SemanticNotNullCoordinates.load(classLoader))
            }
        }

    suspend fun <T> fetchNode(
        context: ResolverExecutionContext<out Query>,
        dbRead: DbRead,
        ownedSelections: SelectionSet<T>,
        requestedSelections: SelectionSet<T>,
    ): T where T : CompositeOutput, T : NodeObject {
        val references = nodeReferencePlanner.plan(requestedSelections, ownedSelections)
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
