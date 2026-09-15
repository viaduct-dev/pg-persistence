@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.connection
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type

/** The reflected fields and response writers for one conventional Viaduct connection. */
@Suppress("LongParameterList") // Preserve the existing descriptor constructor and copy API.
internal class ConnectionShape(
    val type: Type<*>,
    val edgeField: CompositeField<*, *>,
    val edge: EdgeShape,
    val nodesField: CompositeField<*, *>?,
    val pageInfoField: CompositeField<*, *>?,
    val pageInfo: PageInfoShape?,
    val pathResolver: ConnectionPathResolver = PgGraphqlConnectionPathResolver,
    requestedFieldNames: Set<String>? = null,
) {
    val requestedFieldNames: Set<String>? =
        requestedFieldNames?.let { java.util.Collections.unmodifiableSet(LinkedHashSet(it)) }

    fun copy(
        type: Type<*> = this.type,
        edgeField: CompositeField<*, *> = this.edgeField,
        edge: EdgeShape = this.edge,
        nodesField: CompositeField<*, *>? = this.nodesField,
        pageInfoField: CompositeField<*, *>? = this.pageInfoField,
        pageInfo: PageInfoShape? = this.pageInfo,
        pathResolver: ConnectionPathResolver = this.pathResolver,
        requestedFieldNames: Set<String>? = this.requestedFieldNames,
    ): ConnectionShape =
        ConnectionShape(
            type,
            edgeField,
            edge,
            nodesField,
            pageInfoField,
            pageInfo,
            pathResolver,
            requestedFieldNames,
        )

    override fun equals(other: Any?): Boolean =
        other is ConnectionShape &&
            type == other.type &&
            edgeField == other.edgeField &&
            edge == other.edge &&
            nodesField == other.nodesField &&
            pageInfoField == other.pageInfoField &&
            pageInfo == other.pageInfo &&
            pathResolver == other.pathResolver &&
            requestedFieldNames == other.requestedFieldNames

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + edgeField.hashCode()
        result = 31 * result + edge.hashCode()
        result = 31 * result + (nodesField?.hashCode() ?: 0)
        result = 31 * result + (pageInfoField?.hashCode() ?: 0)
        result = 31 * result + (pageInfo?.hashCode() ?: 0)
        result = 31 * result + pathResolver.hashCode()
        result = 31 * result + (requestedFieldNames?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "ConnectionShape(" +
            "type=$type, " +
            "edgeField=$edgeField, " +
            "edge=$edge, " +
            "nodesField=$nodesField, " +
            "pageInfoField=$pageInfoField, " +
            "pageInfo=$pageInfo, " +
            "pathResolver=$pathResolver, " +
            "requestedFieldNames=$requestedFieldNames)"

    val edgeType: Type<*> get() = edge.type
    val nodeField: CompositeField<*, *> get() = edge.node.field
    val cursorField: Field<*>? get() = edge.cursor?.field

    fun path(fieldName: String): ConnectionPath = pathResolver.resolve(fieldName, this)

    /** Compatibility view used by reflection tests and diagnostics. */
    val fields: List<ConnectionResponseField>
        get() = fields()

    /** Fields are selected and restored by their reflected GraphQL field types. */
    fun fields(): List<ConnectionResponseField> =
        buildList {
            if ("nodes" in requestedFieldNames.orEmpty()) {
                nodesField?.let { add(NodesResponseField(it, edgeField, edge)) }
            }
            if (requestedFieldNames == null || "edges" in requestedFieldNames) {
                add(EdgesResponseField(edgeField, edge))
            }
            if (requestedFieldNames == null || "pageInfo" in requestedFieldNames) {
                pageInfoField?.let { field ->
                    pageInfo?.let { shape -> add(PageInfoResponseField(field, shape)) }
                }
            }
        }

    fun upstreamSelection(
        fieldName: String,
        arguments: ConnectionPaginationArguments = ConnectionPaginationArguments.none(),
        typeReflection: GeneratedTypeReflection? = null,
    ): String {
        val path = path(fieldName)
        val selections =
            fields().mapNotNull { field ->
                typeReflection?.let { field.selection(path, it) } ?: field.selection(path)
            }
        return "${path.requestFieldName}${arguments.render()} { ${selections.joinToString(" ")} }"
    }
}
