package dev.viaduct.persistence.pggraphql.translation

import dev.viaduct.persistence.runtime.reflection.AbstractRelationship
import graphql.language.Field
import graphql.language.InlineFragment
import graphql.language.Selection
import graphql.language.SelectionSet

/** Builds concrete target fields and the association-row selection shared by lists and connections. */
internal class AbstractRelationshipSelection(
    private val relationship: AbstractRelationship,
    private val projector: ConcreteSelectionProjector,
) {
    fun translate(field: Field): Selection<*> =
        when {
            !relationship.collection -> targets(field)
            relationship.connectionType != null ->
                AbstractConnectionSelection(relationship, projector, ::targets, ::rowNode).translate(field)
            else ->
                field.transform {
                    it
                        .alias(ABSTRACT_LIST_PREFIX + (field.alias ?: field.name))
                        .selectionSet(selectionSetOf(Field.newField("edges").selectionSet(rowNode(field)).build()))
                }
        }

    private fun targets(field: Field): InlineFragment =
        InlineFragment
            .newInlineFragment()
            .directives(field.directives)
            .selectionSet(
                SelectionSet
                    .newSelectionSet(
                        relationship.targets.map { concrete ->
                            val selected = projector.project(requireNotNull(field.selectionSet), concrete)
                            Field
                                .newField(relationship.targetField(concrete))
                                .alias(abstractAlias(field.alias ?: field.name, concrete))
                                .selectionSet(selected.withTypename())
                                .build()
                        },
                    ).build(),
            ).build()

    private fun rowNode(field: Field): SelectionSet {
        val node = Field.newField("node").selectionSet(field.selectionSet).build()
        val row = selectionSetOf(targets(node))
        return selectionSetOf(Field.newField("node").selectionSet(row).build())
    }
}
