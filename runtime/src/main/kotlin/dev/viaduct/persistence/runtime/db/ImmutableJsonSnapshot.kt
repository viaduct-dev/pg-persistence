package dev.viaduct.persistence.runtime.db

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Snapshots JSON containers, including nested values, without retaining mutable backing collections. */
internal fun JsonElement.immutableSnapshot(): JsonElement =
    when (this) {
        is JsonObject -> JsonObject(immutableJsonObjectValues(this))
        is JsonArray -> JsonArray(java.util.List.copyOf(map(JsonElement::immutableSnapshot)))
        else -> this
    }

internal fun immutableJsonObjectValues(value: JsonObject): Map<String, JsonElement> =
    java.util.Collections.unmodifiableMap(value.mapValues { (_, element) -> element.immutableSnapshot() })
