package dev.viaduct.persistence.pggraphql.translation

import dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings
import graphql.language.Field
import graphql.language.InlineFragment
import graphql.language.Selection
import graphql.language.SelectionSet

/** Filters expanded fragments for one concrete type and delegates each field exactly once. */
internal class ConcreteSelectionProjector(
    private val mappings: AbstractTypeMappings,
    val field: (Field, String) -> Selection<*>,
) {
    fun project(
        set: SelectionSet,
        type: String,
        field: (Field, String) -> Selection<*> = this.field,
    ): SelectionSet {
        val selections =
            set.selections.mapNotNull { selection ->
                when (selection) {
                    is Field -> field(selection, type)
                    is InlineFragment -> {
                        val condition = selection.typeCondition?.name
                        if (condition != null && !mappings.accepts(condition, type)) {
                            null
                        } else {
                            selection.transform {
                                it.typeCondition(null).selectionSet(project(selection.selectionSet, type, field))
                            }
                        }
                    }
                    else -> selection
                }
            }
        return SelectionSet.newSelectionSet(selections.ifEmpty { listOf(Field("__typename")) }).build()
    }
}

internal fun SelectionSet.withTypename(): SelectionSet =
    if (selections.filterIsInstance<Field>().any { it.name == "__typename" && it.alias == null }) {
        this
    } else {
        transform { it.selections(selections + Field("__typename")) }
    }

internal fun selectionSetOf(vararg selections: Selection<*>) = SelectionSet.newSelectionSet(selections.toList()).build()
