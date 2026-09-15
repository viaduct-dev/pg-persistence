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
        references.filter { it.kind == NodeReferenceKind.LIST }.forEach { reference ->
            var page = response.getValue(reference.fieldName).jsonObject
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
                page = load(reference.listSelection(cursor)).getValue(reference.fieldName).jsonObject
                edges.addAll(page.getValue("edges").jsonArray)
            }
            result[reference.fieldName] = JsonObject(page + ("edges" to JsonArray(edges)))
        }
        return JsonObject(result)
    }
}
