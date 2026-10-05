package dev.viaduct.persistence.runtime.reflection

import viaduct.api.context.ExecutionContext
import viaduct.api.reflect.Field

/** Small adapter around the generated builders' reflective API. */
internal class GeneratedBuilder private constructor(
    private val instance: Any,
) {
    private val builderClass = instance::class.java

    fun set(
        fieldName: String,
        value: Any?,
    ): GeneratedBuilder {
        val setter =
            builderClass.methods.singleOrNull {
                it.name == fieldName && it.parameterCount == 1
            } ?: error(
                "Generated builder '${builderClass.name}' has no unique '$fieldName' setter",
            )
        setter.invoke(instance, value)
        return this
    }

    fun set(
        field: Field<*>,
        value: Any?,
    ): GeneratedBuilder = set(field.name, value)

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
