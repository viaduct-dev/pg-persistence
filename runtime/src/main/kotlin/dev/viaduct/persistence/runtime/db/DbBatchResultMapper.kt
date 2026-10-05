package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import viaduct.api.FieldValue
import viaduct.errors.ErroneousFieldException
import viaduct.errors.FieldError

/** Converts one pg_graphql collection response into independently successful or failed nodes. */
internal object DbBatchResultMapper {
    @Suppress("LongParameterList")
    fun <T> map(
        requestedIds: List<String>,
        collectionField: String,
        responseKey: String,
        data: JsonObject?,
        errors: List<UpstreamGraphqlError>,
        hydrate: (JsonObject) -> T,
    ): Map<String, FieldValue<T>> {
        val rawEdges = data?.get("edges") as? JsonArray
        if (rawEdges == null) {
            if (errors.isNotEmpty()) throw errors.toErroneousFieldException()
            error("Db response did not include 'edges' while reading '$collectionField'")
        }

        val rawNodes =
            rawEdges.map { edge ->
                edge.jsonObject["node"]
                    ?.takeUnless { it is JsonNull }
                    ?.jsonObject
            }
        val idsByEdge =
            rawNodes.mapIndexed { index, node ->
                node?.get("uuidId")?.jsonPrimitive?.contentOrNull
                    ?: error(
                        "Db response for '$collectionField' had a node at edge $index with no 'uuidId'",
                    )
            }
        val errorsById = linkedMapOf<String, MutableList<UpstreamGraphqlError>>()
        val unassociatedErrors = mutableListOf<UpstreamGraphqlError>()
        errors.forEach { error ->
            val edgeIndex = error.edgeIndex(responseKey)
            val id = edgeIndex?.let(idsByEdge::getOrNull)
            if (id == null) {
                unassociatedErrors += error
            } else {
                errorsById.getOrPut(id, ::mutableListOf) += error
            }
        }
        if (unassociatedErrors.isNotEmpty()) {
            throw unassociatedErrors.toErroneousFieldException()
        }

        val restoredNodes =
            rawNodes.map { node ->
                PgGraphqlTranslation.restoreViaductResponseShape(requireNotNull(node)).jsonObject
            }
        val nodesById = idsByEdge.zip(restoredNodes).toMap()

        return requestedIds.distinct().associateWith { id ->
            val nodeErrors = errorsById[id]
            when {
                nodeErrors != null -> FieldValue.ofError(nodeErrors.toErroneousFieldException())
                nodesById[id] == null -> FieldValue.ofError(missingRow(collectionField, id))
                else ->
                    runCatching { hydrate(nodesById.getValue(id)) }.fold(
                        onSuccess = FieldValue.Companion::ofValue,
                        onFailure = { FieldValue.ofError(it.asException()) },
                    )
            }
        }
    }

    private fun UpstreamGraphqlError.edgeIndex(responseKey: String): Int? {
        val pathWithoutRoot =
            if (path.firstOrNull()?.jsonPrimitive?.contentOrNull == responseKey) path.drop(1) else path
        val isNodePath =
            pathWithoutRoot.getOrNull(0)?.jsonPrimitive?.contentOrNull == "edges" &&
                pathWithoutRoot.getOrNull(2)?.jsonPrimitive?.contentOrNull == "node"
        return pathWithoutRoot
            .takeIf { isNodePath }
            ?.getOrNull(1)
            ?.jsonPrimitive
            ?.intOrNull
    }

    private fun List<UpstreamGraphqlError>.toErroneousFieldException(): ErroneousFieldException =
        ErroneousFieldException(
            map { error ->
                FieldError(
                    message = error.message,
                    extensions = error.extensions.mapValues { (_, value) -> value.toGraphqlValue() },
                )
            },
        )

    private fun missingRow(
        collectionField: String,
        id: String,
    ): ErroneousFieldException =
        ErroneousFieldException(
            listOf(
                FieldError(
                    message = "$collectionField '$id' was not found",
                    extensions = mapOf("code" to "MISSING_ROW", "internalId" to id),
                ),
            ),
        )

    private fun Throwable.asException(): Exception = this as? Exception ?: RuntimeException(this)

    private fun JsonElement.toGraphqlValue(): Any? =
        when (this) {
            JsonNull -> null
            is JsonObject -> mapValues { (_, value) -> value.toGraphqlValue() }
            is JsonArray -> map { it.toGraphqlValue() }
            is JsonPrimitive ->
                if (isString) content else booleanOrNull ?: intOrNull ?: doubleOrNull ?: content
        }
}
