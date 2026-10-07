@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.connection.ConnectionFetcher
import dev.viaduct.persistence.runtime.connection.CursorProgress
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import dev.viaduct.persistence.runtime.node.NodeReferenceResolver
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Connection
import viaduct.api.types.NodeObject
import viaduct.api.types.Query

internal class LookupFetcher(
    private val transport: PgGraphqlTransport,
    private val reflection: GeneratedTypeReflection,
    private val connections: ConnectionFetcher,
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun <K, N : NodeObject> fetch(
        context: ResolverExecutionContext<out Query>,
        lookup: DbLookup<K, N>,
        key: K,
        orderBy: List<PgGraphqlOrder>,
    ): List<N> {
        val planner = LookupQueryPlanner(lookup, lookup.filter(key), reflection, orderBy = orderBy)
        val result = mutableListOf<N>()
        val resolver = NodeReferenceResolver()
        val progress = CursorProgress("Lookup ${lookup.sourceType.name}")
        var after: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val paging = after?.let { "(after: ${JsonPrimitive(it)})" }.orEmpty()
            val page = planner.connection(checkNotNull(transport.execute(context, planner.query(paging))))
            val edges = page.getValue("edges").jsonArray
            edges.forEach { edge ->
                result += resolver.resolve(context, lookup.nodeType, edge.jsonObject.getValue("node").jsonObject) as N
            }
            val info = page.getValue("pageInfo").jsonObject
            if (!info.getValue("hasNextPage").jsonPrimitive.boolean) return result
            check(edges.isNotEmpty()) { "Lookup returned an empty page with hasNextPage" }
            after =
                checkNotNull(info["endCursor"]?.jsonPrimitive?.contentOrNull) {
                    "Lookup has another page but no endCursor"
                }
            progress.record(after)
        } while (true)
    }

    suspend fun <K, N : NodeObject, R : Connection<*, *>> fetchConnection(
        context: ConnectionFieldExecutionContext<*, *, *, R>,
        lookup: DbLookup<K, N>,
        key: K,
        selections: SelectionSet<R>,
        orderBy: List<PgGraphqlOrder>,
    ): R {
        val shape = requireNotNull(reflection.connection(selections.type, selections))
        require(shape.nodeField.type.kcls == lookup.nodeType.kcls) { "Connection must return ${lookup.nodeType.name}" }
        val planner = LookupQueryPlanner(lookup, lookup.filter(key), reflection, shape, orderBy)
        return connections.fetch(context, selections, planner)
    }
}
