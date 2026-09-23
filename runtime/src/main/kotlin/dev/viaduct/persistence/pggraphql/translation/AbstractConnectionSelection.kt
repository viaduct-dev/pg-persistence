package dev.viaduct.persistence.pggraphql.translation

import dev.viaduct.persistence.runtime.reflection.AbstractRelationship
import graphql.language.Field
import graphql.language.InlineFragment
import graphql.language.Selection
import graphql.language.SelectionSet

/** Owns connection-field traversal; generated selections are never projected a second time. */
internal class AbstractConnectionSelection(
    private val relationship: AbstractRelationship,
    private val projector: ConcreteSelectionProjector,
    private val targets: (Field) -> InlineFragment,
    private val rowNode: (Field) -> SelectionSet,
) {
    fun translate(field: Field): Field =
        field.transform {
            it.selectionSet(
                projector.project(
                    requireNotNull(field.selectionSet),
                    requireNotNull(relationship.connectionType),
                    ::connectionField,
                ),
            )
        }

    private fun connectionField(
        field: Field,
        parent: String,
    ): Selection<*> =
        when (requireNotNull(field.name) { "GraphQL fields must have a name" }) {
            "nodes" ->
                field.transform {
                    it
                        .name("edges")
                        .alias(ABSTRACT_NODES_PREFIX + (field.alias ?: field.name))
                        .selectionSet(rowNode(field))
                }
            "__typename" -> field.publicTypename(parent)
            "edges" -> edges(field)
            else -> projector.field(field, parent)
        }

    private fun edges(field: Field): Field {
        val projected =
            projector.project(
                requireNotNull(field.selectionSet),
                requireNotNull(relationship.edgeType),
            ) { selection, parent ->
                when (requireNotNull(selection.name) { "GraphQL fields must have a name" }) {
                    "node" -> targets(selection)
                    "__typename" -> selection.publicTypename(parent)
                    else -> projector.field(selection, parent)
                }
            }
        return field.transform {
            it
                .alias(VIADUCT_ASSOCIATION_EDGES_ALIAS_PREFIX + (field.alias ?: field.name))
                .selectionSet(AssociationEdgeSelections(projected).selectionSet())
        }
    }
}

internal fun Field.publicTypename(type: String): Field = transform { it.alias(typeAlias(alias ?: name, type)) }
