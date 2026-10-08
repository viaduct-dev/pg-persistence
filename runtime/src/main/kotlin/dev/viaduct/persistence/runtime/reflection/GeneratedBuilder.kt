package dev.viaduct.persistence.runtime.reflection

import viaduct.api.context.ExecutionContext
import viaduct.api.reflect.Field
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.time.Instant
import java.time.OffsetDateTime
import java.lang.reflect.Array as ReflectArray

/** Small adapter around the generated builders' reflective API. */
internal class GeneratedBuilder private constructor(
    private val instance: Any,
) {
    private val builderClass = instance::class.java

    fun set(
        fieldName: String,
        value: Any?,
    ): GeneratedBuilder {
        setter(fieldName).invoke(instance, value)
        return this
    }

    fun set(
        field: Field<*>,
        value: Any?,
    ): GeneratedBuilder = set(field.name, value)

    /** Native backends pass mapped values directly; only container and enum representation differs from GRTs. */
    fun setNative(
        fieldName: String,
        value: Any?,
    ): GeneratedBuilder {
        val setter = setter(fieldName)
        setter.invoke(instance, nativeValue(value, setter.genericParameterTypes.single()))
        return this
    }

    /** Array elements use the same enum/time conversion as scalars, including Kotlin's wildcard bounds. */
    private fun nativeValue(
        value: Any?,
        target: Type,
    ): Any? {
        if (value == null) return null
        return when (target) {
            is WildcardType -> nativeValue(value, target.upperBounds.single())
            is ParameterizedType ->
                if (value.javaClass.isArray) {
                    List(ReflectArray.getLength(value)) {
                        nativeValue(ReflectArray.get(value, it), target.actualTypeArguments.single())
                    }
                } else {
                    value
                }
            is Class<*> ->
                when {
                    target.isEnum && !target.isInstance(value) ->
                        target.enumConstants.single { (it as Enum<*>).name == value.toString() }
                    target == Instant::class.java && value is OffsetDateTime -> value.toInstant()
                    else -> value
                }
            else -> value
        }
    }

    fun setJson(
        field: Field<*>,
        value: kotlinx.serialization.json.JsonElement?,
    ): GeneratedBuilder {
        val setter = setter(field.name)
        setter.invoke(
            instance,
            JsonValueDecoder.decode(value, setter.genericParameterTypes.single()),
        )
        return this
    }

    /** Calls the generated modern Viaduct builder, which owns PageInfo construction. */
    fun fromEdges(
        edges: List<Any>,
        hasNextPage: Boolean,
        hasPreviousPage: Boolean,
    ): GeneratedBuilder {
        builderClass
            .getMethod(
                "fromEdges",
                List::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).invoke(instance, edges, hasNextPage, hasPreviousPage)
        return this
    }

    fun build(): Any = builderClass.getMethod("build").invoke(instance)

    private fun setter(fieldName: String) =
        builderClass.methods.singleOrNull {
            it.name == fieldName && it.parameterCount == 1
        } ?: error(
            "Generated builder '${builderClass.name}' has no unique '$fieldName' setter",
        )

    companion object {
        fun fromObject(value: Any): GeneratedBuilder =
            GeneratedBuilder(
                value::class.java.getMethod("toBuilder").invoke(value),
            )

        fun fromExecutionContext(
            builderClass: Class<*>,
            context: ExecutionContext,
        ): GeneratedBuilder =
            GeneratedBuilder(
                builderClass
                    .getConstructor(ExecutionContext::class.java)
                    .newInstance(context),
            )
    }
}
