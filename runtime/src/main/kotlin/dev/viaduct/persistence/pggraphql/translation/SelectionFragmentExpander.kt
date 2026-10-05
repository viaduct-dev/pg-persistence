package dev.viaduct.persistence.pggraphql.translation

import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet

/** Expands the root fragment once, before any fields are rewritten into database selections. */
internal class SelectionFragmentExpander(
    document: Document,
) {
    private val fragments = document.definitions.filterIsInstance<FragmentDefinition>().associateBy { it.name }

    fun expand(): FragmentDefinition {
        val main = requireNotNull(fragments["Main"] ?: fragments.values.firstOrNull()) { "Missing selection fragment" }
        return main.transform { it.selectionSet(expand(main.selectionSet, setOf(main.name))) }
    }

    private fun expand(
        set: SelectionSet,
        visiting: Set<String>,
    ): SelectionSet =
        set.transform { builder ->
            builder.selections(
                set.selections.map { selection ->
                    when (selection) {
                        is Field ->
                            selection.selectionSet?.let { nested ->
                                selection.transform { it.selectionSet(expand(nested, visiting)) }
                            } ?: selection
                        is InlineFragment -> {
                            val nested = expand(selection.selectionSet, visiting)
                            selection.transform { it.selectionSet(nested) }
                        }
                        is FragmentSpread -> {
                            require(selection.name !in visiting) { "Cyclic fragment ${selection.name}" }
                            val name = selection.name
                            val fragment = requireNotNull(fragments[name]) { "Missing fragment $name" }
                            InlineFragment
                                .newInlineFragment()
                                .typeCondition(fragment.typeCondition)
                                .directives(fragment.directives + selection.directives)
                                .selectionSet(expand(fragment.selectionSet, visiting + selection.name))
                                .build()
                        }
                        else -> selection
                    }
                },
            )
        }
}
