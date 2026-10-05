package dev.viaduct.persistence.runtime.db

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Collections

internal class DbBatchRows(
    edges: JsonArray,
) {
    val nodes =
        Collections.unmodifiableList(
            edges.map { edge -> (edge as? JsonObject)?.get("node") as? JsonObject },
        )
    val idsByEdge =
        Collections.unmodifiableList(
            nodes.map { node -> (node?.get("uuidId") as? JsonPrimitive)?.contentOrNull },
        )
    val returnedIds = Collections.unmodifiableSet(idsByEdge.filterNotNull().toSet())
}
