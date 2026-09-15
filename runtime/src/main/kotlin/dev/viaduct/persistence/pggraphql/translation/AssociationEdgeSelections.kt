package dev.viaduct.persistence.pggraphql.translation

import graphql.language.Field
import graphql.language.InlineFragment
import graphql.language.Selection
import graphql.language.SelectionSet

/** Splits projected edge selections without losing fragment directives or copying growing lists. */
internal class AssociationEdgeSelections(
    set: SelectionSet,
) {
    private val edges = mutableListOf<Selection<*>>()
    private val rows = mutableListOf<Selection<*>>()

    init {
        set.selections.forEach { selection ->
            when (selection) {
                is InlineFragment -> {
                    val nested = AssociationEdgeSelections(selection.selectionSet)
                    wrap(nested.edges, selection)?.let(edges::add)
                    wrap(nested.rows, selection)?.let(rows::add)
                }
                is Field ->
                    if (selection.name == "cursor" || selection.name == "__typename") {
                        edges.add(selection)
                    } else {
                        rows.add(selection)
                    }
                else -> error("Association edge fragments must be expanded before splitting")
            }
        }
    }

    fun selectionSet(): SelectionSet {
        val selections =
            buildList {
                addAll(edges)
                if (rows.isNotEmpty()) {
                    add(
                        Field
                            .newField("node")
                            .alias(VIADUCT_ASSOCIATION_ROW_ALIAS)
                            .selectionSet(SelectionSet.newSelectionSet(rows).build())
                            .build(),
                    )
                }
            }
        return SelectionSet.newSelectionSet(selections).build()
    }

    private fun wrap(
        selections: List<Selection<*>>,
        fragment: InlineFragment,
    ): InlineFragment? =
        selections.takeIf { it.isNotEmpty() }?.let { nested ->
            fragment.transform { it.selectionSet(SelectionSet.newSelectionSet(nested).build()) }
        }
}
