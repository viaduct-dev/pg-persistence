package dev.viaduct.persistence.runtime.db

import io.mockk.every
import io.mockk.mockk
import viaduct.api.select.OutputSelectionFragment
import viaduct.api.select.SelectionSet

/** A selection for transport tests whose mocked responses do not need generated GRTs. */
internal fun testReadSelections(): SelectionSet<FetchJsonFixtureNode> =
    mockk<SelectionSet<FetchJsonFixtureNode>>().also {
        every { it.isEmpty() } returns false
        every { it.type } returns FetchJsonFixtureType
        every { it.toFragment() } returns
            OutputSelectionFragment("Main", "fragment Main on FetchJsonFixtureNode { __typename }", emptyMap())
    }
