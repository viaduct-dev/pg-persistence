@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.select

import io.mockk.every
import io.mockk.mockk
import viaduct.api.internal.InternalSelectionSet
import viaduct.api.reflect.Type
import viaduct.api.select.OutputSelectionFragment
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.fragment.Fragment
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionSetSupportTest {
    @Test
    fun `engine export preserves aliases fragments and variables without retired public methods`() {
        val engine = mockk<EngineSelectionSet>()
        val selections = engineSelections(engine)
        val document = "fragment Main on User { alias: name ...Details } fragment Details on User { email }"
        val variables = mapOf("includeEmail" to true)
        every { engine.toFragment() } returns Fragment(document, variables)

        assertEquals(OutputSelectionFragment("Main", document, variables), selections.exportFragment())
    }

    @Test
    fun `empty engine selections still export a valid database fragment`() {
        val engine = mockk<EngineSelectionSet>()
        val selections = engineSelections(engine)
        val type = mockk<Type<CompositeOutput>>()
        every { selections.type } returns type
        every { type.name } returns "User"
        every { engine.toFragment() } returns Fragment("", emptyMap())
        every { engine.isTransitivelyEmpty() } returns true

        assertEquals(
            OutputSelectionFragment("Main", "fragment Main on User { __typename }", emptyMap()) to true,
            selections.exportFragment() to selections.hasNoSelections(),
        )
    }

    @Test
    fun `concrete narrowing uses the engine selection type`() {
        val engine = mockk<EngineSelectionSet>()
        val concrete = mockk<EngineSelectionSet>()
        val selections = engineSelections(engine)
        val type = mockk<Type<CompositeOutput>>()
        every { type.name } returns "User"
        every { engine.selectionSetForType("User") } returns concrete
        every { concrete.type } returns "User"
        val narrowed = selections.forConcreteType(type) as InternalSelectionSet

        assertEquals(concrete, narrowed.engineSelectionSet)
    }

    @Test
    fun `older abstract projections expose the concrete root to JSON mapping`() {
        val engine = mockk<EngineSelectionSet>()
        val projected = mockk<EngineSelectionSet>()
        val selections = engineSelections(engine)
        val type = mockk<Type<CompositeOutput>>()
        every { type.name } returns "User"
        every { engine.selectionSetForType("User") } returns projected
        every { projected.type } returns "Node"
        every { projected.isTransitivelyEmpty() } returns false
        val narrowed = selections.forConcreteType(type) as InternalSelectionSet

        assertEquals(
            "User" to false,
            narrowed.engineSelectionSet.type to narrowed.engineSelectionSet.isTransitivelyEmpty(),
        )
    }

    private fun engineSelections(engine: EngineSelectionSet): SelectionSet<CompositeOutput> =
        mockk<SelectionSet<CompositeOutput>>(moreInterfaces = arrayOf(InternalSelectionSet::class)).also {
            every { (it as InternalSelectionSet).engineSelectionSet } returns engine
        }
}
