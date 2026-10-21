@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.select.PersistenceSelectionFragment
import io.mockk.every
import io.mockk.mockk
import viaduct.api.internal.EngineSelectionSetProvider
import viaduct.api.internal.InternalSelectionSet
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.fragment.Fragment

/** Exposes test fixture data through Viaduct 4's internal selection contracts. */
internal interface TestSelectionSet<T : CompositeOutput> :
    InternalSelectionSet<T>,
    EngineSelectionSetProvider {
    fun isEmpty(): Boolean

    fun toFragment(): PersistenceSelectionFragment

    fun <U : T> selectionSetFor(type: Type<U>): SelectionSet<U>
}

internal fun <T : CompositeOutput> mockSelectionSet(
    relaxed: Boolean = false,
    block: TestSelectionSet<T>.() -> Unit = {},
): TestSelectionSet<T> {
    val selections = mockk<TestSelectionSet<T>>(relaxed = relaxed)
    val engine = mockk<EngineSelectionSet>(relaxed = relaxed)
    every { selections.engineSelectionSet } returns engine
    every { engine.type } answers { selections.type.name }
    every { engine.toFragment() } answers {
        val fragment = selections.toFragment()
        Fragment(fragment.document, fragment.variables)
    }
    every { engine.isTransitivelyEmpty() } answers { selections.isEmpty() }
    selections.block()
    return selections
}
