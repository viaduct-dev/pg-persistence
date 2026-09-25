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
internal class EdgeShape(
    val type: Type<*>,
    val node: NodeResponseField,
    val cursor: CursorResponseField?,
    customFields: List<StoredEdgeResponseField> = emptyList(),
    val isAssociationBacked: Boolean = false,
) {
    val customFields: List<StoredEdgeResponseField> = java.util.List.copyOf(customFields)

    fun copy(
        type: Type<*> = this.type,
        node: NodeResponseField = this.node,
        cursor: CursorResponseField? = this.cursor,
        customFields: List<StoredEdgeResponseField> = this.customFields,
        isAssociationBacked: Boolean = this.isAssociationBacked,
    ): EdgeShape = EdgeShape(type, node, cursor, customFields, isAssociationBacked)

    override fun equals(other: Any?): Boolean =
        other is EdgeShape &&
            type == other.type &&
            node == other.node &&
            cursor == other.cursor &&
            customFields == other.customFields &&
            isAssociationBacked == other.isAssociationBacked

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + node.hashCode()
        result = 31 * result + (cursor?.hashCode() ?: 0)
        result = 31 * result + customFields.hashCode()
        result = 31 * result + isAssociationBacked.hashCode()
        return result
    }

    override fun toString(): String =
        "EdgeShape(" +
            "type=$type, " +
            "node=$node, " +
            "cursor=$cursor, " +
            "customFields=$customFields, " +
            "isAssociationBacked=$isAssociationBacked)"

    val fields: List<EdgeResponseField> = java.util.List.copyOf(listOfNotNull(cursor, node) + this.customFields)

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
