package dev.viaduct.persistence.runtime.connection
import dev.viaduct.persistence.runtime.reflection.GeneratedFieldReflection
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Type
import viaduct.api.types.CompositeOutput

/** Discovers the structural Viaduct connection descriptor for a generated type. */
internal class ConnectionShapeFactory(
    private val reflection: GeneratedFieldReflection,
    private val storageClassifier: ConnectionStorageClassifier = ConnectionStorageClassifier(reflection),
    private val pathResolver: ConnectionPathResolver = PgGraphqlConnectionPathResolver,
) {
    fun create(
        type: Type<*>,
        requestedSelections: viaduct.api.select.SelectionSet<*>?,
        ownerType: Type<*>? = null,
    ): ConnectionShape? =
        reflection.field(type, "edges")?.let { edgeField ->
            reflection.field(edgeField.type, "node")?.let { nodeField ->
                createShape(type, edgeField, nodeField, requestedSelections, ownerType)
            }
        }

    private fun createShape(
        type: Type<*>,
        edgeField: viaduct.api.reflect.CompositeField<*, *>,
        nodeField: viaduct.api.reflect.CompositeField<*, *>,
        requestedSelections: viaduct.api.select.SelectionSet<*>?,
        ownerType: Type<*>?,
    ): ConnectionShape {
        val edgeType = edgeField.type
        val pageInfoField = reflection.field(type, "pageInfo")
        val edgeSelections =
            requestedSelections?.let {
                reflection.childSelections(it, edgeField)
            }
        val pageInfoSelections =
            requestedSelections?.let { selections ->
                pageInfoField?.let { reflection.childSelections(selections, it) }
            }
        val selectedConnectionFields =
            requestedSelections
                ?.selectedFieldCoordinates()
                ?.map { it.fieldName }
                ?.toSet()
        val selectedEdgeFields =
            edgeSelections
                ?.selectedFieldCoordinates()
                ?.map { it.fieldName }
                ?.toSet()
        val edge =
            EdgeShape(
                type = edgeType,
                node = NodeResponseField(nodeField),
                cursor = reflection.anyField(edgeType, "cursor")?.let(::CursorResponseField),
                customFields = customEdgeFields(edgeType, edgeSelections, selectedEdgeFields),
                isAssociationBacked =
                    ownerType?.let { storageClassifier.isAssociationBacked(it, type) }
                        ?: hasCustomEdgeFields(edgeType),
            )
        return ConnectionShape(
            type = type,
            edgeField = edgeField,
            edge = edge,
            nodesField = reflection.field(type, "nodes"),
            pageInfo =
                pageInfoField?.let {
                    PageInfoFieldFactory.create(
                        it.type,
                        reflection,
                        pageInfoSelections
                            ?.selectedFieldCoordinates()
                            ?.map { field -> field.fieldName }
                            ?.toSet(),
                    )
                },
            pageInfoField = pageInfoField,
            pathResolver = pathResolver,
            requestedFieldNames = selectedConnectionFields,
        )
    }

    private fun hasCustomEdgeFields(edgeType: Type<*>): Boolean =
        reflection.allFields(edgeType).any { it.name !in setOf("node", "cursor", "__typename") }

    private fun customEdgeFields(
        edgeType: Type<*>,
        edgeSelections: viaduct.api.select.SelectionSet<*>?,
        selectedEdgeFields: Set<String>?,
    ): List<StoredEdgeResponseField> =
        reflection
            .allFields(edgeType)
            .filterNot { it.name in setOf("node", "cursor", "__typename") }
            .filter { selectedEdgeFields == null || it.name in selectedEdgeFields }
            .map { field ->
                val selections =
                    (field as? CompositeField<*, *>)
                        ?.takeIf { CompositeOutput::class.java.isAssignableFrom(it.type.kcls.java) }
                        ?.let { composite -> edgeSelections?.let { reflection.childSelections(it, composite) } }
                customEdgeResponseField(field, selections)
            }
}
