package dev.viaduct.persistence.pggraphql.translation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal class ResponseShapeRestorer {
    private val fieldRestorers: List<ResponseFieldRestorer> =
        listOf(
            AssociationConnectionFieldRestorer(),
            AssociationEdgesFieldRestorer(),
            AssociationNodesFieldRestorer(),
            ViaductNodesFieldRestorer(),
            NestedResponseFieldRestorer(),
        )

    fun restore(response: JsonElement): JsonElement =
        when (response) {
            is JsonObject -> restoreObject(response)
            is JsonArray -> JsonArray(response.map(::restore))
            else -> response
        }

    /** Restores a pg_graphql error path using the aliases that define response restoration. */
    fun restorePath(path: List<JsonElement>): List<JsonElement> = ResponsePathRestorer.restore(path)

    private fun restoreObject(response: JsonObject): JsonObject =
        JsonObject(
            AbstractResponseRestorer.restore(response, ::restore).entries.associate { (key, value) ->
                val restorer = fieldRestorers.first { it.supports(key, value) }
                val restored = restorer.restore(key, value, ::restore)
                restored.key to restored.value
            },
        )
}

@Suppress("MagicNumber")
private object ResponsePathRestorer {
    fun restore(path: List<JsonElement>): List<JsonElement> {
        val restored = mutableListOf<JsonElement>()
        var index = 0
        while (index < path.size) {
            val segment = path[index]
            val key = (segment as? JsonPrimitive)?.content
            val consumed = restoreSegment(path, index, key, restored)
            index += consumed
        }
        return restored
    }

    private fun restoreSegment(
        path: List<JsonElement>,
        index: Int,
        key: String?,
        restored: MutableList<JsonElement>,
    ): Int =
        when {
            key?.startsWith(ABSTRACT_NODES_PREFIX) == true -> restoreAbstractNodes(path, index, key, restored)
            key?.startsWith(ABSTRACT_LIST_PREFIX) == true -> {
                restored += JsonPrimitive(key.removePrefix(ABSTRACT_LIST_PREFIX))
                if (path.textAt(index + 1) == "edges") {
                    path.getOrNull(index + 2)?.let(restored::add)
                    3 + abstractRowPathLength(path, index + 3)
                } else {
                    1
                }
            }
            key?.startsWith(ABSTRACT_TYPE_PREFIX) == true -> {
                restored += JsonPrimitive(decodeAbstractAlias(key, ABSTRACT_TYPE_PREFIX).first)
                1
            }
            key?.startsWith(ABSTRACT_ALIAS_PREFIX) == true -> {
                restored += JsonPrimitive(decodeAbstractAlias(key).first)
                1
            }
            key == VIADUCT_NODES_RESPONSE_ALIAS -> restoreNodes(path, index, restored)
            key?.startsWith(VIADUCT_ASSOCIATION_NODES_ALIAS_PREFIX) == true ->
                restoreAssociationNodes(path, index, key, restored)
            key?.startsWith(VIADUCT_ASSOCIATION_EDGES_ALIAS_PREFIX) == true ->
                restoreAssociationEdges(path, index, key, restored)
            key?.startsWith(VIADUCT_ASSOCIATION_CONNECTION_ALIAS_PREFIX) == true -> {
                restored +=
                    JsonPrimitive(
                        responseKeyFromInternalAlias(VIADUCT_ASSOCIATION_CONNECTION_ALIAS_PREFIX, key),
                    )
                1
            }
            key?.startsWith(VIADUCT_ASSOCIATION_NODE_ALIAS_PREFIX) == true -> {
                restored += JsonPrimitive(responseKeyFromInternalAlias(VIADUCT_ASSOCIATION_NODE_ALIAS_PREFIX, key))
                1
            }
            else -> {
                restored += path[index]
                1
            }
        }

    private fun restoreAbstractNodes(
        path: List<JsonElement>,
        index: Int,
        key: String,
        restored: MutableList<JsonElement>,
    ): Int {
        restored += JsonPrimitive(key.removePrefix(ABSTRACT_NODES_PREFIX))
        path.getOrNull(index + 1)?.let(restored::add)
        return 2 + abstractRowPathLength(path, index + 2)
    }

    private fun abstractRowPathLength(
        path: List<JsonElement>,
        index: Int,
    ): Int =
        when {
            path.textAt(index) != "node" -> 0
            path.textAt(index + 1)?.startsWith(ABSTRACT_ALIAS_PREFIX) == true -> 2
            else -> 1
        }

    private fun restoreNodes(
        path: List<JsonElement>,
        index: Int,
        restored: MutableList<JsonElement>,
    ): Int {
        restored += JsonPrimitive("nodes")
        path.getOrNull(index + 1)?.let(restored::add)
        return if (path.textAt(index + 2) == "node") 3 else 2
    }

    private fun restoreAssociationNodes(
        path: List<JsonElement>,
        index: Int,
        key: String,
        restored: MutableList<JsonElement>,
    ): Int {
        restored += JsonPrimitive(responseKeyFromInternalAlias(VIADUCT_ASSOCIATION_NODES_ALIAS_PREFIX, key))
        path.getOrNull(index + 1)?.let(restored::add)
        return if (
            path.textAt(index + 2) == "node" &&
            path.textAt(index + 3)?.startsWith(VIADUCT_ASSOCIATION_NODE_ALIAS_PREFIX) == true
        ) {
            4
        } else {
            2
        }
    }

    private fun restoreAssociationEdges(
        path: List<JsonElement>,
        index: Int,
        key: String,
        restored: MutableList<JsonElement>,
    ): Int {
        restored += JsonPrimitive(responseKeyFromInternalAlias(VIADUCT_ASSOCIATION_EDGES_ALIAS_PREFIX, key))
        path.getOrNull(index + 1)?.let(restored::add)
        val rowKey = path.textAt(index + 2)
        val legacyRow = rowKey == "node" && path.getOrNull(index + 3) != null
        if (rowKey != VIADUCT_ASSOCIATION_ROW_ALIAS && !legacyRow) return 2
        val nodeAlias = path.textAt(index + 3)
        return if (nodeAlias?.startsWith(VIADUCT_ASSOCIATION_NODE_ALIAS_PREFIX) == true) {
            restored += JsonPrimitive(responseKeyFromInternalAlias(VIADUCT_ASSOCIATION_NODE_ALIAS_PREFIX, nodeAlias))
            4
        } else if (nodeAlias?.startsWith(ABSTRACT_ALIAS_PREFIX) == true) {
            restored += JsonPrimitive(decodeAbstractAlias(nodeAlias).first)
            4
        } else {
            3
        }
    }

    private fun List<JsonElement>.textAt(index: Int): String? = (getOrNull(index) as? JsonPrimitive)?.content
}

private class AssociationConnectionFieldRestorer : ResponseFieldRestorer {
    override fun supports(
        key: String,
        value: JsonElement,
    ): Boolean = key.startsWith(VIADUCT_ASSOCIATION_CONNECTION_ALIAS_PREFIX) && value is JsonObject

    override fun restore(
        key: String,
        value: JsonElement,
        restore: (JsonElement) -> JsonElement,
    ): RestoredResponseField =
        RestoredResponseField(
            key = responseKeyFromInternalAlias(VIADUCT_ASSOCIATION_CONNECTION_ALIAS_PREFIX, key),
            value = restore(value),
        )
}

private class AssociationEdgesFieldRestorer : ResponseFieldRestorer {
    override fun supports(
        key: String,
        value: JsonElement,
    ): Boolean = key.startsWith(VIADUCT_ASSOCIATION_EDGES_ALIAS_PREFIX)

    override fun restore(
        key: String,
        value: JsonElement,
        restore: (JsonElement) -> JsonElement,
    ): RestoredResponseField {
        val responseKey = responseKeyFromInternalAlias(VIADUCT_ASSOCIATION_EDGES_ALIAS_PREFIX, key)
        return RestoredResponseField(
            key = responseKey,
            value =
                if (value is JsonNull) {
                    value
                } else {
                    JsonArray(
                        value.jsonArray.map {
                            if (it is JsonNull) it else flattenEdge(restore(it).jsonObject)
                        },
                    )
                },
        )
    }

    private fun flattenEdge(edge: JsonObject): JsonObject {
        val rowKey = if (VIADUCT_ASSOCIATION_ROW_ALIAS in edge) VIADUCT_ASSOCIATION_ROW_ALIAS else "node"
        val row =
            edge[rowKey] as? JsonObject
                ?: return if (rowKey == VIADUCT_ASSOCIATION_ROW_ALIAS) JsonObject(edge - rowKey) else edge
        val flattened = linkedMapOf<String, JsonElement>()
        edge.forEach { (key, value) ->
            if (key != rowKey) flattened[key] = value
        }
        row.forEach { (key, value) ->
            flattened[responseKeyFromInternalAlias(VIADUCT_ASSOCIATION_NODE_ALIAS_PREFIX, key)] = value
        }
        return JsonObject(flattened)
    }
}

private class AssociationNodesFieldRestorer : ResponseFieldRestorer {
    override fun supports(
        key: String,
        value: JsonElement,
    ): Boolean = key.startsWith(VIADUCT_ASSOCIATION_NODES_ALIAS_PREFIX) && value is JsonArray

    override fun restore(
        key: String,
        value: JsonElement,
        restore: (JsonElement) -> JsonElement,
    ): RestoredResponseField {
        val responseKey = responseKeyFromInternalAlias(VIADUCT_ASSOCIATION_NODES_ALIAS_PREFIX, key)
        return RestoredResponseField(
            key = responseKey,
            value =
                JsonArray(
                    value.jsonArray.map { edge ->
                        val restoredEdge = restore(edge).jsonObject
                        val row = requireNotNull(restoredEdge["node"] as? JsonObject)
                        val nodeAlias = row.keys.first { it.startsWith(VIADUCT_ASSOCIATION_NODE_ALIAS_PREFIX) }
                        requireNotNull(row[nodeAlias])
                    },
                ),
        )
    }
}

private data class RestoredResponseField(
    val key: String,
    val value: JsonElement,
)

private interface ResponseFieldRestorer {
    fun supports(
        key: String,
        value: JsonElement,
    ): Boolean

    fun restore(
        key: String,
        value: JsonElement,
        restore: (JsonElement) -> JsonElement,
    ): RestoredResponseField
}

private class ViaductNodesFieldRestorer : ResponseFieldRestorer {
    override fun supports(
        key: String,
        value: JsonElement,
    ): Boolean = key == VIADUCT_NODES_RESPONSE_ALIAS && value is JsonArray

    override fun restore(
        key: String,
        value: JsonElement,
        restore: (JsonElement) -> JsonElement,
    ): RestoredResponseField =
        RestoredResponseField(
            key = "nodes",
            value =
                JsonArray(
                    value.jsonArray.map { edge ->
                        restore(edge.jsonObject["node"] ?: edge)
                    },
                ),
        )
}

private class NestedResponseFieldRestorer : ResponseFieldRestorer {
    override fun supports(
        key: String,
        value: JsonElement,
    ): Boolean = true

    override fun restore(
        key: String,
        value: JsonElement,
        restore: (JsonElement) -> JsonElement,
    ): RestoredResponseField =
        RestoredResponseField(
            key = key,
            value = restore(value),
        )
}
