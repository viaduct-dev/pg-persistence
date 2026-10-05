package dev.viaduct.persistence.runtime.node

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Plain lists have no page arguments: follow provider cursors before returning their references. */
internal object NodeListPager {
    suspend fun complete(
        response: JsonObject,
        references: List<NodeReferenceSelection>,
        load: suspend (String) -> JsonObject,
    ): JsonObject {
        val result = response.toMutableMap()
        references.filter(NodeReferenceSelection::isPlainList).forEach { reference ->
            var page = response.getValue(reference.listResponseKey).jsonObject
            val edges = page.getValue("edges").jsonArray.toMutableList()
            val cursors = mutableSetOf<String>()
            while (page
                    .getValue("pageInfo")
                    .jsonObject
                    .getValue("hasNextPage")
                    .jsonPrimitive.boolean
            ) {
                val cursor =
                    requireNotNull(
                        page
                            .getValue("pageInfo")
                            .jsonObject["endCursor"]
                            ?.jsonPrimitive
                            ?.contentOrNull,
                    ) {
                        "Db list '${reference.fieldName}' has another page but no endCursor"
                    }
                check(cursors.add(cursor)) { "Db list '${reference.fieldName}' repeated cursor '$cursor'" }
                page = load(reference.listSelection(cursor)).getValue(reference.listResponseKey).jsonObject
                edges.addAll(page.getValue("edges").jsonArray)
            }
            if (reference.kind == NodeReferenceKind.ABSTRACT) {
                result.remove(reference.listResponseKey)
                result[reference.fieldName] =
                    JsonArray(edges.map { edge -> edge.jsonObject.getValue("node") })
            } else {
                result[reference.fieldName] = JsonObject(page + ("edges" to JsonArray(edges)))
            }
        }
        return JsonObject(result)
    }
}
