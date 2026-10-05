package dev.viaduct.persistence.pggraphql.translation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class RestoredAbstractField(
    val key: String,
    val value: JsonElement,
    val isTarget: Boolean = false,
)

private class AbstractResponseFieldTransformer(
    val prefix: String,
    val restore: (String, JsonElement, (JsonElement) -> JsonElement) -> RestoredAbstractField,
)

internal object AbstractResponseFieldTransformers {
    // Specific aliases must be matched before the shared concrete-target prefix.
    private val transformers =
        listOf(
            AbstractResponseFieldTransformer(ABSTRACT_NODES_PREFIX, ::restoreConnectionNodes),
            AbstractResponseFieldTransformer(ABSTRACT_LIST_PREFIX, ::restoreList),
            AbstractResponseFieldTransformer(ABSTRACT_TYPE_PREFIX) { key, value, _ -> restoreTypename(key, value) },
            AbstractResponseFieldTransformer(ABSTRACT_ALIAS_PREFIX, ::restoreTarget),
        )

    fun restore(
        key: String,
        value: JsonElement,
        nested: (JsonElement) -> JsonElement,
    ): RestoredAbstractField {
        val transformer =
            transformers.firstOrNull { key.startsWith(it.prefix) }
                ?: return RestoredAbstractField(key, value)
        return transformer.restore(key, value, nested)
    }
}

private fun restoreConnectionNodes(
    key: String,
    value: JsonElement,
    nested: (JsonElement) -> JsonElement,
): RestoredAbstractField = RestoredAbstractField(key.removePrefix(ABSTRACT_NODES_PREFIX), restoreNodes(value, nested))

private fun restoreList(
    key: String,
    value: JsonElement,
    nested: (JsonElement) -> JsonElement,
): RestoredAbstractField {
    val edges = if (value is JsonNull) value else value.jsonObject.getValue("edges")
    return RestoredAbstractField(key.removePrefix(ABSTRACT_LIST_PREFIX), restoreNodes(edges, nested))
}

private fun restoreTypename(
    key: String,
    value: JsonElement,
): RestoredAbstractField {
    val (responseKey, type) = decodeAbstractAlias(key, ABSTRACT_TYPE_PREFIX)
    return RestoredAbstractField(responseKey, if (value is JsonNull) value else JsonPrimitive(type))
}

private fun restoreTarget(
    key: String,
    value: JsonElement,
    nested: (JsonElement) -> JsonElement,
): RestoredAbstractField {
    val (responseKey, type) = decodeAbstractAlias(key)
    val restored =
        if (value is JsonNull) {
            value
        } else {
            val data = nested(value).jsonObject
            require(data["__typename"]?.jsonPrimitive?.content == type) {
                "Abstract field $responseKey expected concrete type $type, got ${data["__typename"]}"
            }
            data
        }
    return RestoredAbstractField(responseKey, restored, isTarget = true)
}

private fun restoreNodes(
    value: JsonElement,
    nested: (JsonElement) -> JsonElement,
): JsonElement =
    if (value is JsonNull) {
        value
    } else {
        JsonArray(
            value.jsonArray.map { edge ->
                val row = (edge as? JsonObject)?.get("node")
                if (row == null || row is JsonNull) JsonNull else nested(row).jsonObject.getValue("node")
            },
        )
    }
