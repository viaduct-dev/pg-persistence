package dev.viaduct.persistence.gradle

import graphql.language.BooleanValue
import graphql.language.ObjectTypeExtensionDefinition
import graphql.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SelectiveNodeSchemaTest {
    @Test
    fun `contributes metadata only for implemented nodes including inherited Node interfaces`() {
        val schema =
            """
            interface Node { id: ID! }
            interface Entity implements Node { id: ID! }
            type Group implements Entity { id: ID!, name: String }
            type Person implements Node { id: ID! }
            type Unimplemented implements Node { id: ID! }
            """.trimIndent()
        val result = contributions(schema, mapOf("Group" to true, "Person" to false))
        val extensions = Parser.parse(result).definitions.filterIsInstance<ObjectTypeExtensionDefinition>()
        assertEquals(listOf("Group", "Person"), extensions.map { it.name })
        assertEquals(listOf(true, true), extensions.map { argument(it, "isSelective") })
        assertEquals(listOf(true, false), extensions.map { argument(it, "isBatching") })
    }

    @Test
    fun `preserves explicit metadata on base types and extensions`() {
        for (directive in listOf("@resolver", "@resolver(isSelective: false)", "@resolver(isSelective: true)")) {
            assertEquals(
                "",
                contributions("type Group implements Node $directive { id: ID! }", mapOf("Group" to false)),
            )
            assertEquals(
                "",
                contributions(
                    "type Group implements Node { id: ID! }\nextend type Group $directive",
                    mapOf("Group" to false),
                ),
            )
        }
    }

    @Test
    fun `rejects implementations referring to unknown or non-node schema objects`() {
        for (schema in listOf("", "type Group { name: String }")) {
            assertFailsWith<IllegalArgumentException> { contributions(schema, mapOf("Group" to false)) }
        }
    }

    private fun contributions(
        schema: String,
        resolvers: Map<String, Boolean>,
    ) = SelectiveNodeSchema.contributions(mapOf("schema.graphqls" to "interface Node { id: ID! }\n$schema"), resolvers)

    private fun argument(
        extension: ObjectTypeExtensionDefinition,
        name: String,
    ): Boolean =
        (
            extension.directives
                .single()
                .arguments
                .single { it.name == name }
                .value as BooleanValue
        ).isValue
}
