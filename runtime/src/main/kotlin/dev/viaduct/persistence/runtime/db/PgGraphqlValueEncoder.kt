package dev.viaduct.persistence.runtime.db

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import viaduct.api.globalid.GlobalID
import viaduct.api.types.Input

/** Encodes the open value types exposed by generated Viaduct input data. */
@Suppress("MaxLineLength")
internal fun Any?.toPgGraphqlJsonElement(): JsonElement = pgGraphqlValueEncoders.firstNotNullOf { encoder -> encoder(this) }

private val pgGraphqlValueEncoders: List<(Any?) -> JsonElement?> =
    listOf(
        ::encodeNull,
        ::encodeJsonElement,
        ::encodePgGraphqlObject,
        ::encodeGlobalId,
        ::encodeInput,
        ::encodeMap,
        ::encodeCollection,
        ::encodeScalar,
        ::encodeFallback,
    )

private fun encodeNull(value: Any?): JsonElement? = if (value == null) JsonNull else null

private fun encodeJsonElement(value: Any?): JsonElement? = value as? JsonElement

private fun encodePgGraphqlObject(value: Any?): JsonElement? = (value as? PgGraphqlObject)?.encoded()

private fun encodeGlobalId(value: Any?): JsonElement? = (value as? GlobalID<*>)?.let { JsonPrimitive(it.internalID) }

private fun encodeInput(value: Any?): JsonElement? = (value as? Input)?.toPgGraphqlInput()

private fun encodeMap(value: Any?): JsonElement? =
    (value as? Map<*, *>)?.let { map ->
        buildJsonObject {
            map.forEach { (key, fieldValue) ->
                put(key.toString(), fieldValue.toPgGraphqlJsonElement())
            }
        }
    }

private fun encodeCollection(value: Any?): JsonElement? {
    val values =
        when (value) {
            is Iterable<*> -> value
            is Array<*> -> value.asIterable()
            else -> return null
        }
    return buildJsonArray { values.forEach { add(it.toPgGraphqlJsonElement()) } }
}

private fun encodeScalar(value: Any?): JsonElement? =
    when (value) {
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Enum<*> -> JsonPrimitive(value.name)
        else -> null
    }

private fun encodeFallback(value: Any?): JsonElement = JsonPrimitive(value.toString())
