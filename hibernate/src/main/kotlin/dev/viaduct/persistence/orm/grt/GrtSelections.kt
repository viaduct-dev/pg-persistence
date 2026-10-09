@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.orm.grt

import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput

/** Merge nodes and edges.node using only Viaduct's public selection coordinates. */
fun <T : CompositeOutput> mergeSelections(
    first: SelectionSet<T>,
    second: SelectionSet<T>,
): SelectionSet<T> {
    require(first.type.kcls == second.type.kcls) { "Selections must have the same type" }
    return when {
        first.isEmpty() -> second
        second.isEmpty() -> first
        else ->
            object : SelectionSet<T> {
                override val type: Type<T> = first.type

                override fun selectedFieldCoordinates() =
                    first.selectedFieldCoordinates() + second.selectedFieldCoordinates()

                override fun isEmpty(): Boolean = false

                override fun <U : T> contains(field: Field<U>): Boolean =
                    first.contains(field) || second.contains(field)

                override fun <U : T> requestsType(type: Type<U>): Boolean =
                    first.requestsType(type) || second.requestsType(type)

                override fun <U : T, R : CompositeOutput> selectionSetFor(
                    field: CompositeField<U, R>,
                ): SelectionSet<R> = mergeSelections(first.selectionSetFor(field), second.selectionSetFor(field))

                override fun <U : T> selectionSetFor(type: Type<U>): SelectionSet<U> =
                    mergeSelections(first.selectionSetFor(type), second.selectionSetFor(type))
            }
    }
}

/** Include an edge's node even for nodes-only/pageInfo-only requests; it identifies each edge. */
fun <T : CompositeOutput, N : CompositeOutput> withNodeSelections(
    selections: SelectionSet<T>,
    node: CompositeField<T, N>,
    children: SelectionSet<N>,
): SelectionSet<T> =
    object : SelectionSet<T> by selections {
        override fun selectedFieldCoordinates() =
            selections.selectedFieldCoordinates() +
                viaduct.api.select.FieldCoordinate(node.containingType.name, node.name)

        override fun isEmpty(): Boolean = false

        override fun <U : T> contains(field: Field<U>): Boolean =
            (field.name == node.name && field.containingType.kcls == node.containingType.kcls) ||
                selections.contains(field)

        @Suppress("UNCHECKED_CAST") // Equality with this typed node coordinate establishes the child type.
        override fun <U : T, R : CompositeOutput> selectionSetFor(
            field: CompositeField<U, R>,
        ): SelectionSet<R> =
            if (field.name == node.name &&
                field.containingType.kcls == node.containingType.kcls
            ) {
                children as SelectionSet<R>
            } else {
                selections.selectionSetFor(field)
            }
    }
