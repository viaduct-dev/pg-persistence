@file:OptIn(
    viaduct.apiannotations.ExperimentalApi::class,
    viaduct.apiannotations.InternalApi::class,
)

package dev.viaduct.persistence.runtime.connection

import dev.viaduct.persistence.runtime.db.DbRead
import dev.viaduct.persistence.runtime.db.PgGraphqlFilter
import dev.viaduct.persistence.runtime.db.PgGraphqlOrder
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import dev.viaduct.persistence.runtime.node.NodeReferenceResolver
import dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings
import dev.viaduct.persistence.runtime.reflection.GeneratedBuilder
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.reflect.CompositeField
import viaduct.api.select.SelectionSet
import viaduct.api.types.Connection
import viaduct.api.types.OffsetCursor
import viaduct.api.types.OffsetLimit

/** Adapts Viaduct's offset bounds to pg_graphql; no provider cursor escapes this class. */
internal class ConnectionFetcher(
    private val transport: PgGraphqlTransport,
    private val reflection: GeneratedTypeReflection,
) {
    @Suppress("UNCHECKED_CAST", "LongParameterList")
    suspend fun <R : Connection<*, *>> fetch(
        context: ConnectionFieldExecutionContext<*, *, *, R>,
        read: DbRead,
        selections: SelectionSet<R>,
        field: CompositeField<*, *>?,
        filter: PgGraphqlFilter,
        orderBy: List<PgGraphqlOrder>,
    ): R {
        context.arguments.validate()
        require(field == null || (read.root.singleViaFilteredCollection && field.type.kcls == selections.type.kcls)) {
            "Nested connections require a single filtered parent and matching connection selections"
        }
        require(field != null || !read.root.singleViaFilteredCollection) {
            "Root connections require a collection, not a single node"
        }
        PagingAccess.validateRoot(read.root)
        if (selections.selectedFieldCoordinates().all { it.fieldName == "__typename" }) {
            context.arguments.toOffsetLimit(totalCount = 0)
            return GeneratedBuilder.fromExecutionContext(reflection.builderClass(selections.type), context).build() as R
        }
        val reflected = checkNotNull(reflection.connection(selections.type, selections, field?.containingType))
        val abstract =
            field?.let {
                AbstractTypeMappings
                    .load(it.containingType.kcls.java.classLoader)
                    .relationship(it.containingType.name, it.name)
            }
        val shape =
            if (abstract == null) {
                reflected
            } else {
                reflected.copy(edge = reflected.edge.copy(isAssociationBacked = false))
            }
        val planner =
            ConnectionQueryPlanner(
                read,
                shape.copy(requestedFieldNames = null),
                field,
                reflection,
                filter,
                orderBy,
            )
        val bounds =
            if (context.arguments.requiresTotalCountForOffsetLimit()) {
                context.arguments.toOffsetLimit(totalCount = count(context, planner))
            } else {
                context.arguments.toOffsetLimit()
            }
        val page = slice(context, planner, bounds)
        val path = if (field == null) ConnectionPath(read.root.field) else shape.path(field.name)
        return buildConnection(context, selections, shape, page, bounds, path)
    }

    @Suppress("UNCHECKED_CAST", "LongParameterList")
    private fun <R : Connection<*, *>> buildConnection(
        context: ConnectionFieldExecutionContext<*, *, *, R>,
        selections: SelectionSet<R>,
        shape: ConnectionShape,
        page: Page,
        bounds: OffsetLimit,
        path: ConnectionPath,
    ): R {
        val nodeResolver = NodeReferenceResolver()
        val edges =
            page.edges.mapIndexed { index, edge ->
                val cursor = OffsetCursor.fromOffset(Math.addExact(bounds.offset, index)).value
                shape.edge.build(
                    JsonObject(edge + ("cursor" to JsonPrimitive(cursor))),
                    EdgeBuildContext(index, selections.type.name, context, reflection, nodeResolver, path),
                )
            }
        val builder =
            GeneratedBuilder
                .fromExecutionContext(reflection.builderClass(selections.type), context)
                .fromEdges(edges, page.hasNextPage, bounds.offset > 0)
        shape.nodesField?.let { field ->
            // Each response location needs its own reference: nodes and edges can request
            // different selections and finish materializing at different times.
            val nodes = page.edges.map { shape.edge.node.resolve(it, path, context, nodeResolver) }
            builder.set(field.name, nodes)
        }
        return builder.build() as R
    }

    /** Count cursor metadata a page at a time when totalCount is disabled on a table. */
    private suspend fun count(
        context: ConnectionFieldExecutionContext<*, *, *, *>,
        planner: ConnectionQueryPlanner,
    ): Int {
        val progress = CursorProgress("Connection count")
        var total = 0
        var after: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val arguments = after?.let { "(after: ${JsonPrimitive(it)})" }.orEmpty()
            val page = readPage(context, planner, arguments, countOnly = true)
            total = Math.addExact(total, page.edges.size)
            if (!page.hasNextPage) return total
            check(page.edges.isNotEmpty()) { "Connection count returned an empty page with hasNextPage" }
            after = checkNotNull(page.endCursor) { "Connection count has another page but no endCursor" }
            progress.record(after)
        } while (true)
    }

    private suspend fun slice(
        context: ConnectionFieldExecutionContext<*, *, *, *>,
        planner: ConnectionQueryPlanner,
        bounds: OffsetLimit,
    ): Page {
        val edges = mutableListOf<JsonObject>()
        do {
            currentCoroutineContext().ensureActive()
            val remaining = maxOf(1, bounds.limit - edges.size)
            val offset = Math.addExact(bounds.offset, edges.size)
            val page = readPage(context, planner, "(first: $remaining, offset: $offset)")
            check(page.edges.size <= remaining) { "Connection returned more edges than requested" }
            if (bounds.limit == 0) return Page(emptyList(), page.edges.isNotEmpty(), null)
            edges.addAll(page.edges)
            if (!page.hasNextPage || edges.size == bounds.limit) return Page(edges, page.hasNextPage, null)
            check(page.edges.isNotEmpty()) { "Connection slice returned an empty page with hasNextPage" }
        } while (true)
    }

    private suspend fun readPage(
        context: ConnectionFieldExecutionContext<*, *, *, *>,
        planner: ConnectionQueryPlanner,
        arguments: String,
        countOnly: Boolean = false,
    ): Page {
        val raw = checkNotNull(transport.execute(context, planner.query(arguments, countOnly)))
        val response = planner.connection(raw)
        val pageInfo = response.getValue("pageInfo").jsonObject
        return Page(
            response.getValue("edges").jsonArray.map { it.jsonObject },
            pageInfo.getValue("hasNextPage").jsonPrimitive.boolean,
            pageInfo["endCursor"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private data class Page(
        val edges: List<JsonObject>,
        val hasNextPage: Boolean,
        val endCursor: String?,
    )
}
