package dev.viaduct.persistence.runtime.reflection

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.time.Instant
import java.util.UUID

/** Converts JSON values returned by pg_graphql to generated builder parameter types. */
internal object JsonValueDecoder {
    private val untypedClassTypes = setOf(Any::class.java, Object::class.java)

    fun decode(
        value: JsonElement?,
        targetType: Type,
    ): Any? {
        if (value == null || value is JsonNull) return null
        return when (targetType) {
            is ParameterizedType -> decodeParameterized(value, targetType)
            is GenericArrayType -> decodeArray(value, targetType.genericComponentType)
            is Class<*> -> decodeClass(value, targetType)
            else -> decodeUntyped(value)
        }
    }

    private fun decodeParameterized(
        value: JsonElement,
        type: ParameterizedType,
    ): Any? {
        val rawType = type.rawType as? Class<*> ?: return decodeUntyped(value)
        val arguments = type.actualTypeArguments
        return when {
            Collection::class.java.isAssignableFrom(rawType) -> {
                val elementType = arguments.singleOrNull() ?: Any::class.java
                value.jsonArray.map { decode(it, elementType) }
            }
            Map::class.java.isAssignableFrom(rawType) -> {
                val valueType = arguments.getOrNull(1) ?: Any::class.java
                value.jsonObject.mapValues { (_, entry) -> decode(entry, valueType) }
            }
            else -> decodeClass(value, rawType)
        }
    }

    private fun decodeArray(
        value: JsonElement,
        componentType: Type,
    ): Array<Any?> = value.jsonArray.map { decode(it, componentType) }.toTypedArray()

    private fun decodeClass(
        value: JsonElement,
        type: Class<*>,
    ): Any? =
        when {
            JsonElement::class.java.isAssignableFrom(type) -> value
            type in untypedClassTypes -> decodeUntyped(value)
            Collection::class.java.isAssignableFrom(type) -> decodeUntypedCollection(value)
            Map::class.java.isAssignableFrom(type) -> decodeUntypedMap(value)
            type.isArray -> decodeUntypedArray(value)
            else -> decodeScalar(value, type)
        }

    private fun decodeUntypedCollection(value: JsonElement): List<Any?> = value.jsonArray.map(::decodeUntyped)

    private fun decodeUntypedMap(value: JsonElement): Map<String, Any?> =
        value.jsonObject.mapValues(
            transform = { (_, entry) ->
                decodeUntyped(entry)
            },
        )

    private fun decodeUntypedArray(value: JsonElement): Array<Any?> =
        value.jsonArray
            .map(::decodeUntyped)
            .toTypedArray()

    private fun decodeScalar(
        value: JsonElement,
        type: Class<*>,
    ): Any? {
        val content = value.jsonPrimitive.content
        return when (type.name) {
            "java.lang.String", "java.lang.CharSequence" -> content
            "boolean", "java.lang.Boolean" -> content.toBooleanStrict()
            "int", "java.lang.Integer" -> content.toInt()
            "long", "java.lang.Long" -> content.toLong()
            "short", "java.lang.Short" -> content.toShort()
            "byte", "java.lang.Byte" -> content.toByte()
            "double", "java.lang.Double" -> content.toDouble()
            "float", "java.lang.Float" -> content.toFloat()
            "java.math.BigDecimal" -> content.toBigDecimal()
            "java.math.BigInteger" -> content.toBigInteger()
            "java.time.Instant" -> Instant.parse(content)
            "java.util.UUID" -> UUID.fromString(content)
            else -> decodeEnumOrUntyped(value, type, content)
        }
    }

    private fun decodeEnumOrUntyped(
        value: JsonElement,
        type: Class<*>,
        content: String,
    ): Any? =
        if (type.isEnum) {
            type.enumConstants.single { (it as Enum<*>).name == content }
        } else {
            decodeUntyped(value)
        }

    private fun decodeUntyped(value: JsonElement): Any? =
        when (value) {
            is JsonObject -> value.mapValues { (_, entry) -> decodeUntyped(entry) }
            is JsonArray -> value.map(::decodeUntyped)
            is JsonNull -> null
            else -> {
                val primitive = value.jsonPrimitive
                when {
                    primitive.isString -> primitive.content
                    primitive.content == "true" -> true
                    primitive.content == "false" -> false
                    primitive.content.toIntOrNull() != null -> primitive.content.toInt()
                    primitive.content.toLongOrNull() != null -> primitive.content.toLong()
                    primitive.content.toBigDecimalOrNull() != null -> primitive.content.toBigDecimal()
                    else -> primitive.content
                }
            }
        }
}
