@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.select

import viaduct.api.internal.EngineSelectionSetProvider
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.engine.api.EngineSelectionSet
import viaduct.tenant.runtime.select.SelectionSetImpl

/** Export selections through Viaduct's internal engine bridge. */
internal fun SelectionSet<*>.exportFragment(): PersistenceSelectionFragment {
    require(this is EngineSelectionSetProvider) { "Persistence requires engine-backed selections" }
    val fragment = engineSelectionSet.toFragment()
    return PersistenceSelectionFragment(
        "Main",
        fragment.document.ifEmpty { "fragment Main on ${type.name} { __typename }" },
        fragment.variables.asMap(),
    )
}

internal fun SelectionSet<*>.hasNoSelections(): Boolean =
    if (this is EngineSelectionSetProvider) {
        engineSelectionSet.isTransitivelyEmpty()
    } else {
        selectedFieldCoordinates().isEmpty()
    }

internal fun <T : CompositeOutput> SelectionSet<T>.forConcreteType(concreteType: Type<T>): SelectionSet<T> =
    if (this is EngineSelectionSetProvider) {
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
        require(hasNoSelections()) { "Persistence requires engine-backed selections" }
        SelectionSet.empty(concreteType)
    }

internal data class PersistenceSelectionFragment(
    val name: String,
    val document: String,
    val variables: Map<String, Any?>,
)
