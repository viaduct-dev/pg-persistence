@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.select

import viaduct.api.internal.InternalSelectionSet
import viaduct.api.reflect.Type
import viaduct.api.select.OutputSelectionFragment
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.engine.api.EngineSelectionSet
import viaduct.tenant.runtime.select.SelectionSetImpl

/** Engine-backed selections support both Viaduct selection API generations. */
internal fun SelectionSet<*>.exportFragment(): OutputSelectionFragment {
    if (this !is InternalSelectionSet) return toFragment()
    val fragment = engineSelectionSet.toFragment()
    return OutputSelectionFragment(
        "Main",
        fragment.document.ifEmpty { "fragment Main on ${type.name} { __typename }" },
        fragment.variables.asMap(),
    )
}

internal fun SelectionSet<*>.hasNoSelections(): Boolean =
    if (this is InternalSelectionSet) engineSelectionSet.isTransitivelyEmpty() else isEmpty()

internal fun <T : CompositeOutput> SelectionSet<T>.forConcreteType(concreteType: Type<T>): SelectionSet<T> =
    if (this is InternalSelectionSet) {
        val projected = engineSelectionSet.selectionSetForType(concreteType.name)
        // Older engine projections retain the abstract root name used by JsonDomain.
        val concrete =
            if (projected.type == concreteType.name) {
                projected
            } else {
                object : EngineSelectionSet by projected {
                    override val type: String = concreteType.name
                }
            }
        SelectionSetImpl(concreteType, concrete)
    } else {
        selectionSetFor(concreteType)
    }
