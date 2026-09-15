@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.connection
import dev.viaduct.persistence.runtime.node.NodeReferenceResolver
import dev.viaduct.persistence.runtime.reflection.GeneratedBuilder
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import kotlinx.serialization.json.JsonObject
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.types.Query

/** A reflected edge and typed writers for its node, cursor, and custom fields. */
internal data class EdgeShape(
    val type: Type<*>,
    val node: NodeResponseField,
    val cursor: CursorResponseField?,
    val customFields: List<StoredEdgeResponseField> = emptyList(),
    val isAssociationBacked: Boolean = false,
) {
    val fields: List<EdgeResponseField> = listOfNotNull(cursor, node) + customFields

    fun build(
        edge: JsonObject,
        context: EdgeBuildContext,
    ): Any {
        val builder =
            GeneratedBuilder.fromExecutionContext(
                context.typeReflection.builderClass(type),
                context.executionContext,
            )
        fields.forEach {
            it.write(
                builder,
                edge,
                context.executionContext,
                context.nodeResolver,
                context.path,
            )
        }
        return try {
            builder.build()
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException(
                "Could not build connection edge at index ${context.index} " +
                    "for '${context.connectionTypeName}'",
                error,
            )
        }
    }
}

internal data class EdgeBuildContext(
    val index: Int,
    val connectionTypeName: String,
    val executionContext: ResolverExecutionContext<out Query>,
    val typeReflection: GeneratedTypeReflection,
    val nodeResolver: NodeReferenceResolver,
    val path: ConnectionPath,
)

internal interface EdgeResponseField {
    val field: Field<*>

    fun selection(path: ConnectionPath): String

    fun selection(
        path: ConnectionPath,
        typeReflection: GeneratedTypeReflection,
    ): String = selection(path)

    fun selection(): String = selection(ConnectionPath(requestFieldName = ""))

    fun write(
        builder: GeneratedBuilder,
        response: JsonObject,
        context: ResolverExecutionContext<out Query>,
        nodeResolver: NodeReferenceResolver,
        path: ConnectionPath,
    )
}

/** Custom fields live on the association row; expose their selection without the pg_graphql node wrapper. */
internal interface StoredEdgeResponseField : EdgeResponseField {
    fun valueSelection(typeReflection: GeneratedTypeReflection? = null): String

    override fun selection(path: ConnectionPath): String = "node { ${valueSelection()} }"

    override fun selection(
        path: ConnectionPath,
        typeReflection: GeneratedTypeReflection,
    ): String = "node { ${valueSelection(typeReflection)} }"
}
