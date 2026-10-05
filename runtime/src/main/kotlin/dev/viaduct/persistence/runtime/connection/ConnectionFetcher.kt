package dev.viaduct.persistence.runtime.connection

import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import viaduct.api.context.ExecutionContext

/** Executes UUID-oriented connection reads without exposing GraphQL response details to callers. */
internal class ConnectionFetcher(
    private val transport: PgGraphqlTransport,
    private val queryPlanner: ConnectionQueryPlanner = ConnectionQueryPlanner(),
) {
    suspend fun fetchUuidIds(
        context: ExecutionContext,
        collectionField: String,
        arguments: String,
        variableDefinitions: String,
        variables: JsonObject,
    ): List<String> =
        ConnectionResponseDecoder.uuidIds(
            transport.execute(
                context,
                queryPlanner.uuidIds(collectionField, arguments, variableDefinitions, variables),
            ),
            collectionField,
        )

    suspend fun fetchUuidConnection(
        context: ExecutionContext,
        request: ConnectionPageRequest,
    ): UuidConnectionPage =
        ConnectionResponseDecoder.page(
            transport.execute(context, queryPlanner.page(request)),
            request.collectionField,
        )

    suspend fun fetchNestedUuidConnections(
        context: ExecutionContext,
        request: NestedConnectionPageRequest,
    ): Map<String, UuidConnectionPage> {
        if (request.parentIds.isEmpty()) return emptyMap()
        val pages = linkedMapOf<String, UuidConnectionPage>()
        val cursors = CursorProgress("Db parent collection")
        var after: String? = null
        do {
            val data = transport.execute(context, queryPlanner.nested(request, after))
            val page = decodeParentPage(data, request)
            pages.putAll(page.children)
            if (!page.hasNextPage) break
            after = page.endCursor ?: error("Db parent collection has another page but no endCursor")
            cursors.record(after)
        } while (true)
        return pages
    }

    private fun decodeParentPage(
        data: JsonObject,
        request: NestedConnectionPageRequest,
    ): ParentPage {
        val parents =
            data["edges"]?.jsonArray
                ?: error("Db response for '${request.parentCollectionField}' did not include 'edges'")
        val children = linkedMapOf<String, UuidConnectionPage>()
        parents.forEach { edge ->
            val node = edge.jsonObject["node"]?.jsonObject ?: return@forEach
            val parentId = node["uuidId"]?.jsonPrimitive?.content ?: return@forEach
            val child =
                node[request.child.collectionField]?.jsonObject
                    ?: error(
                        "Db response for '${request.parentCollectionField}' parent '$parentId' " +
                            "did not include '${request.child.collectionField}'",
                    )
            children[parentId] = ConnectionResponseDecoder.page(child, request.child.collectionField)
        }
        val pageInfo =
            data["pageInfo"]?.jsonObject
                ?: error("Db response for '${request.parentCollectionField}' did not include 'pageInfo'")
        val hasNextPage =
            pageInfo["hasNextPage"]?.jsonPrimitive?.boolean
                ?: error("Db parent collection pageInfo did not include 'hasNextPage'")
        val endCursor = if (hasNextPage) pageInfo["endCursor"]?.jsonPrimitive?.contentOrNull else null
        return ParentPage(children, hasNextPage, endCursor)
    }

    private data class ParentPage(
        val children: Map<String, UuidConnectionPage>,
        val hasNextPage: Boolean,
        val endCursor: String?,
    )
}
