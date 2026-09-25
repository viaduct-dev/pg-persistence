package dev.viaduct.persistence.pggraphql.translation

import dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings
import graphql.language.AstPrinter
import graphql.language.FragmentDefinition
import graphql.language.SelectionSet
import graphql.parser.Parser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConcreteSelectionProjectorTest {
    @Test
    fun `applicable fragments preserve aliases and directives`() {
        val result =
            project(
                """
            fragment Main on Actor {
                ...Details @include(if: true)
                ... on Group { description }
            }
            fragment Details on Actor @skip(if: false) { display: name }
            """,
            )
        assertEquals(
            compact("{ ... @skip(if: false) @include(if: true) { display: name } }"),
            AstPrinter.printAstCompact(result),
        )
    }

    @Test
    fun `empty concrete projection selects typename`() {
        assertEquals(
            compact("{ __typename }"),
            AstPrinter.printAstCompact(project("fragment Main on Actor { ... on Group { name } }")),
        )
    }

    @Test
    fun `sibling fragment reuse is not a cycle`() {
        val result =
            project(
                """
            fragment Main on Actor { ...Details ...Details }
            fragment Details on Actor { name }
            """,
            )
        assertEquals(compact("{ ... { name } ... { name } }"), AstPrinter.printAstCompact(result))
    }

    @Test
    fun `cyclic fragments are rejected`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                project("fragment Main on Actor { ...Details } fragment Details on Actor { ...Main }")
            }
        assertEquals("Cyclic fragment Main", failure.message)
    }

    @Test
    fun `missing fragments are rejected`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                project("fragment Main on Actor { ...Missing }")
            }
        assertEquals("Missing fragment Missing", failure.message)
    }

    private fun project(source: String): SelectionSet {
        val main = SelectionFragmentExpander(Parser().parseDocument(source)).expand()
        val mappings = AbstractTypeMappings(possibleTypes = mapOf("Actor" to setOf("Person", "Group")))
        val projector = ConcreteSelectionProjector(mappings) { field, _ -> field }
        return projector.project(main.selectionSet, "Person")
    }

    private fun compact(selection: String): String {
        val fragment =
            Parser()
                .parseDocument("fragment Main on Actor $selection")
                .definitions
                .single() as FragmentDefinition
        return AstPrinter.printAstCompact(fragment.selectionSet)
    }
}
