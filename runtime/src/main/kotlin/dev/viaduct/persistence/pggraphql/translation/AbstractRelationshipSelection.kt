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
                    val responseKey = field.alias ?: field.name
                    it
                        .alias(ABSTRACT_LIST_PREFIX + responseKey)
                        .selectionSet(
                            plainListSelection(
                                field,
                                includePageInfo = responseKey.startsWith(ABSTRACT_LIST_PAGE_PREFIX),
                            ),
                        )
                }
        }

    private fun plainListSelection(
        field: Field,
        includePageInfo: Boolean,
    ): SelectionSet {
        val edges = Field.newField("edges").selectionSet(rowNode(field)).build()
        if (!includePageInfo) return selectionSetOf(edges)
        val pageInfo =
            Field
                .newField("pageInfo")
                .selectionSet(selectionSetOf(Field("hasNextPage"), Field("endCursor")))
                .build()
        return selectionSetOf(edges, pageInfo)
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
