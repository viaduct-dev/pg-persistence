package dev.viaduct.persistence.gradle

import graphql.language.ObjectTypeDefinition
import graphql.parser.Parser
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SelectiveNodeSchemaTest {
    @Test
    fun `defaults apply to nodes across files and interfaces but not denied or ordinary objects`() {
        val prepared =
            SelectiveNodeSchema.prepare(
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
        val objects =
            Parser
                .parse(prepared.getValue("Model.graphqls"))
                .definitions
                .filterIsInstance<ObjectTypeDefinition>()
        assertEquals(setOf("Group", "Person"), objects.filter { it.hasDirective("resolver") }.map { it.name }.toSet())
        assertFalse(prepared.getValue("Interface.graphqls").contains("@resolver"))
    }

    @Test
    fun `preserves batching and other directives and normalizes a resolver on an extension only once`() {
        val prepared =
            SelectiveNodeSchema.prepare(
                mapOf(
                    "Model.graphqls" to
                        """
                        type Group implements Node @scope(to: ["app"]) { id: ID }
                        extend type Group @resolver(isBatching: true)
                        type Person implements Node @resolver(isSelective: true) { id: ID }
                        """.trimIndent(),
                ),
                emptySet(),
            )
        val schema = prepared.getValue("Model.graphqls")
        assertEquals(2, Regex("@resolver").findAll(schema).count())
        assertContains(schema, "extend type Group @resolver")
        assertContains(schema, "isBatching: true")
        assertContains(schema, "isSelective: true")
        assertContains(schema, "@scope(to: [\"app\"])")
        assertEquals(prepared, SelectiveNodeSchema.prepare(prepared, emptySet()))
    }

    @Test
    fun `does not add a resolver to a type owned by another module`() {
        val prepared =
            SelectiveNodeSchema.prepare(
                mapOf("Extension.graphqls" to "extend type External implements Node { id: ID! }"),
                emptySet(),
            )
        assertFalse(prepared.getValue("Extension.graphqls").contains("@resolver"))
    }

    @Test
    fun `explicit false reports the node and how to resolve the conflict`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                SelectiveNodeSchema.prepare(
                    mapOf("Model.graphqls" to "type Group implements Node @resolver(isSelective: false) { id: ID }"),
                    emptySet(),
                )
            }
        assertContains(error.message.orEmpty(), "Model.graphqls: Group")
        assertContains(error.message.orEmpty(), "denyList.types")
    }

    @Test
    fun `rejects duplicate resolver declarations instead of choosing one silently`() {
        assertFailsWith<IllegalArgumentException> {
            SelectiveNodeSchema.prepare(
                mapOf(
                    "Model.graphqls" to
                        """
                        type Group implements Node @resolver { id: ID }
                        extend type Group @resolver
                        """.trimIndent(),
                ),
                emptySet(),
            )
        }
    }
}
