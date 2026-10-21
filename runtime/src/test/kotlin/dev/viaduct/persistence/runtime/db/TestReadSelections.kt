package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.db.mockSelectionSet
import dev.viaduct.persistence.runtime.select.PersistenceSelectionFragment
import io.mockk.every
import viaduct.api.select.SelectionSet

/** A selection for transport tests whose mocked responses do not need generated GRTs. */
internal fun testReadSelections(): SelectionSet<FetchJsonFixtureNode> =
    mockSelectionSet<FetchJsonFixtureNode>().also {
        every { it.isEmpty() } returns false
        every { it.type } returns FetchJsonFixtureType
        every { it.toFragment() } returns
            PersistenceSelectionFragment("Main", "fragment Main on FetchJsonFixtureNode { __typename }", emptyMap())
    }
