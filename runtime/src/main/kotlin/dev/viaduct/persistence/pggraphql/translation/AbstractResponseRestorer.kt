package dev.viaduct.persistence.pggraphql.translation

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Applies field transformers, then merges concrete targets into their public response fields. */
internal object AbstractResponseRestorer {
    fun restore(
        response: JsonObject,
        nested: (JsonElement) -> JsonElement,
    ): JsonObject {
        val result = linkedMapOf<String, JsonElement>()
        val targets = linkedMapOf<String, MutableList<JsonElement>>()
        response.forEach { (key, value) ->
            val field = AbstractResponseFieldTransformers.restore(key, value, nested)
            if (field.isTarget) {
                targets.getOrPut(field.key) { mutableListOf() }.add(field.value)
            } else {
                result[field.key] = field.value
            }
        }
        targets.forEach { (key, values) ->
            val present = values.filterNot { it is JsonNull }
            require(present.size <= 1) { "Abstract field $key returned multiple concrete targets" }
            require(key !in result) { "Abstract response field $key collides with another selected field" }
            result[key] = present.singleOrNull() ?: JsonNull
        }
        return JsonObject(result)
    }
}
