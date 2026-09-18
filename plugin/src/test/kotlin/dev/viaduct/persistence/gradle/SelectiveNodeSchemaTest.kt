package dev.viaduct.persistence.gradle

import graphql.language.AstPrinter
import graphql.language.ObjectTypeExtensionDefinition
import graphql.parser.Parser
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SelectiveNodeSchemaTest {
    @Test
    fun `contributes selective resolvers for owned nodes but not denied or ordinary objects`() {
        val contribution =
            SelectiveNodeSchema.contributions(
                mapOf(
                    "Interface.graphqls" to "interface Named implements Node { id: ID, name: String }",
                    "Model.graphqls" to
                        """
                        type Group implements Named { id: ID, name: String }
                        type Person implements Node { id: ID }
                        type External implements Node { id: ID }
                        type Payload { group: Group }
                        """.trimIndent(),
                ),
                setOf("External"),
            )
        val extensions =
            Parser
                .parse(contribution)
                .definitions
                .filterIsInstance<ObjectTypeExtensionDefinition>()

        assertEquals(setOf("Group", "Person"), extensions.map { it.name }.toSet())
        extensions.forEach { assertContains(AstPrinter.printAst(it), "@resolver(isSelective: true)") }
    }

    @Test
    fun `does not duplicate an existing selective resolver`() {
        val contribution =
            SelectiveNodeSchema.contributions(
                mapOf(
                    "Model.graphqls" to
                        """
                        type Group implements Node @scope(to: ["app"]) { id: ID }
                        extend type Group @resolver(isBatching: true, isSelective: true)
                        type Person implements Node @resolver(selective: true) { id: ID }
                        """.trimIndent(),
                ),
                emptySet(),
            )

        assertEquals("", contribution)
    }

    @Test
    fun `does not add a resolver to a type owned by another module`() {
        val contribution =
            SelectiveNodeSchema.contributions(
                mapOf("Extension.graphqls" to "extend type External implements Node { id: ID! }"),
                emptySet(),
            )

        assertEquals("", contribution)
    }

    @Test
    fun `existing nonselective resolver reports the node and resolution`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                SelectiveNodeSchema.contributions(
                    mapOf("Model.graphqls" to "type Group implements Node @resolver(isBatching: true) { id: ID }"),
                    emptySet(),
                )
            }

        assertContains(error.message.orEmpty(), "Model.graphqls: Group")
        assertContains(error.message.orEmpty(), "isSelective: true")
        assertContains(error.message.orEmpty(), "denyList.types")
    }

    @Test
    fun `rejects duplicate resolver declarations instead of choosing one silently`() {
        assertFailsWith<IllegalArgumentException> {
            SelectiveNodeSchema.contributions(
                mapOf(
                    "Model.graphqls" to
                        """
                        type Group implements Node @resolver(isSelective: true) { id: ID }
                        extend type Group @resolver(isSelective: true)
                        """.trimIndent(),
                ),
                emptySet(),
            )
        }
    }
}
