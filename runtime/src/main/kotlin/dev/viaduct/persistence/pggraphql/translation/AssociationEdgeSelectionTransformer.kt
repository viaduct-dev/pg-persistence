package dev.viaduct.persistence.pggraphql.translation

import graphql.language.Field
import graphql.language.SelectionSet

/** Projects concrete edge fields, then uses the same edge/row split as abstract connections. */
internal class AssociationEdgeSelectionTransformer {
    fun transform(
        field: Field,
        connectionType: String,
        context: SelectionTransformContext,
        children: (SelectionSet, SelectionTransformContext) -> SelectionSet,
    ): Field {
        val edgeType = requireNotNull(context.schema.fieldType(connectionType, "edges"))
        val projector =
            ConcreteSelectionProjector(context.schema.abstractTypes) { selection, parent ->
                when (requireNotNull(selection.name) { "GraphQL fields must have a name" }) {
                    "__typename" -> selection.publicTypename(parent)
                    else -> transformField(selection, parent, context, children)
                }
            }
        val projected = projector.project(requireNotNull(field.selectionSet), edgeType)
        return field.transform {
            it
                .alias(internalAssociationAlias(VIADUCT_ASSOCIATION_EDGES_ALIAS_PREFIX, field.alias ?: field.name))
                .selectionSet(AssociationEdgeSelections(projected).selectionSet())
        }
    }

    private fun transformField(
        field: Field,
        parentType: String,
        context: SelectionTransformContext,
        children: (SelectionSet, SelectionTransformContext) -> SelectionSet,
    ): Field {
        val targetType = context.schema.fieldType(parentType, field.name)
        return field.transform { builder ->
            if (field.selectionSet != null && targetType != null) {
                val nested = children(requireNotNull(field.selectionSet), context.copy(parentType = targetType))
                builder.selectionSet(nested)
            }
            if (field.name == "node") {
                val key = field.alias ?: field.name
                builder.alias(internalAssociationAlias(VIADUCT_ASSOCIATION_NODE_ALIAS_PREFIX, key))
            }
        }
    }
}
