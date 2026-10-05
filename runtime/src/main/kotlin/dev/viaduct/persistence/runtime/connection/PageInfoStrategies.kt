@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.connection
import dev.viaduct.persistence.runtime.node.NodeReferenceResolver
import dev.viaduct.persistence.runtime.reflection.GeneratedBuilder
import dev.viaduct.persistence.runtime.reflection.GeneratedFieldReflection
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Query

internal class PageInfoShape(
    val type: Type<*>,
    fields: List<PageInfoField>,
) {
    val fields: List<PageInfoField> = java.util.List.copyOf(fields)

    fun selection(): String? {
        val names = fields.map { it.field.name }
        return names.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    fun build(
        response: JsonObject,
        context: ResolverExecutionContext<out Query>,
        typeReflection: GeneratedTypeReflection,
        nodeResolver: NodeReferenceResolver,
    ): Any =
        GeneratedBuilder
            .fromExecutionContext(typeReflection.builderClass(type), context)
            .also { builder ->
                fields.forEach { it.write(builder, response, context, nodeResolver) }
            }.build()
}

internal interface PageInfoField {
    val field: Field<*>

    fun write(
        builder: GeneratedBuilder,
        response: JsonObject,
        context: ResolverExecutionContext<out Query>,
        nodeResolver: NodeReferenceResolver,
    )
}

internal object PageInfoFieldFactory {
    private val standardNames =
        listOf(
            "hasNextPage",
            "hasPreviousPage",
            "startCursor",
            "endCursor",
        )

    fun create(
        type: Type<*>,
        fieldReflection: GeneratedFieldReflection,
        selectedFieldNames: Set<String>? = null,
    ): PageInfoShape =
        PageInfoShape(
            type = type,
            fields =
                fieldReflection
                    .allFields(type)
                    .filterNot { it.name == "__typename" }
                    .filter { selectedFieldNames == null || it.name in selectedFieldNames }
                    .sortedWith(
                        compareBy({
                            standardNames.indexOf(it.name).takeUnless { index ->
                                index < 0
                            } ?: Int.MAX_VALUE
                        }, Field<*>::name),
                    ).map { field ->
                        when (field.name) {
                            "hasNextPage", "hasPreviousPage" -> BooleanPageInfoField(field)
                            "startCursor", "endCursor" -> CursorPageInfoField(field)
                            else -> customPageInfoField(field)
                        }
                    },
        )
}

private class BooleanPageInfoField(
    override val field: Field<*>,
) : PageInfoField {
    override fun write(
        builder: GeneratedBuilder,
        response: JsonObject,
        context: ResolverExecutionContext<out Query>,
        nodeResolver: NodeReferenceResolver,
    ) {
        builder.set(field, response.requiredBoolean(field.name))
    }
}

private class CursorPageInfoField(
    override val field: Field<*>,
) : PageInfoField {
    override fun write(
        builder: GeneratedBuilder,
        response: JsonObject,
        context: ResolverExecutionContext<out Query>,
        nodeResolver: NodeReferenceResolver,
    ) {
        builder.set(field, response.optionalCursor(field.name))
    }
}

private class JsonPageInfoField(
    override val field: Field<*>,
) : PageInfoField {
    override fun write(
        builder: GeneratedBuilder,
        response: JsonObject,
        context: ResolverExecutionContext<out Query>,
        nodeResolver: NodeReferenceResolver,
    ) {
        builder.setJson(field, response[field.name])
    }
}

private class NodePageInfoField(
    override val field: CompositeField<*, *>,
) : PageInfoField {
    override fun write(
        builder: GeneratedBuilder,
        response: JsonObject,
        context: ResolverExecutionContext<out Query>,
        nodeResolver: NodeReferenceResolver,
    ) {
        val internalId =
            response[field.name]
                ?.takeUnless { it is JsonNull }
                ?.jsonObject
                ?.get("uuidId")
                ?.jsonPrimitive
                ?.content
        builder.set(field, internalId?.let { nodeResolver.resolve(context, field.type, it) })
    }
}

private fun customPageInfoField(field: Field<*>): PageInfoField =
    when {
        field is CompositeField<*, *> && NodeObject::class.java.isAssignableFrom(field.type.kcls.java) ->
            NodePageInfoField(field)
        field is CompositeField<*, *> &&
            CompositeOutput::class.java.isAssignableFrom(field.type.kcls.java) ->
            error(
                "Custom page-info field '${field.name}' must target a Node object or scalar value",
            )
        else -> JsonPageInfoField(field)
    }

private fun JsonObject.optionalCursor(fieldName: String): String? =
    this[fieldName]
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.content

private fun JsonObject.requiredBoolean(fieldName: String): Boolean =
    this[fieldName]
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.content
        ?.toBooleanStrictOrNull()
        ?: error("Db response pageInfo did not include '$fieldName'")
