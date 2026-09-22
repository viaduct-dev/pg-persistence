package dev.viaduct.persistence.gradle

import assertk.assertThat
import assertk.assertions.contains
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.graphql.schema.graphqljava.extensions.ViaductSchemaFactory
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SelectiveNodeValidationTest {
    @ParameterizedTest
    @ValueSource(strings = ["", "@resolver", "@resolver(isSelective: false)", "@resolver(isBatching: true)"])
    fun `requires an explicit selective resolver`(directive: String) {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                validate("type Group implements Node $directive { id: ID! }")
            }

        assertThat(failure.message.orEmpty())
            .contains("Persistent Node 'Group' requires an explicit @resolver(isSelective: true)")
        assertThat(failure.message.orEmpty()).contains("denyList.types")
    }

    @Test
    fun `accepts explicit selective batch resolvers`() {
        validate("type Group implements Node @resolver(isSelective: true, isBatching: true) { id: ID! }")
    }

    @Test
    fun `accepts resolver declarations on an extension`() {
        validate(
            """
            type Group implements Node { id: ID! }
            extend type Group @resolver(isSelective: true)
            """.trimIndent(),
        )
    }

    @Test
    fun `does not require resolvers on excluded nodes or nonpersistent objects`() {
        validate(
            """
            type Group implements Node @resolver(isSelective: true) { id: ID! }
            type External implements Node { id: ID! }
            type Payload { group: Group }
            """.trimIndent(),
        )
    }

    @Test
    fun `requires selective resolution for nodes implementing an intermediate interface`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                validate(
                    """
                    interface Named implements Node { id: ID!, name: String }
                    type Group implements Named & Node { id: ID!, name: String }
                    """.trimIndent(),
                )
            }

        assertThat(failure.message.orEmpty()).contains("Persistent Node 'Group'")
    }

    private fun validate(sdl: String) {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                directive @resolver(isSelective: Boolean = false, isBatching: Boolean = false) on OBJECT
                interface Node { id: ID! }
                $sdl
                """.trimIndent(),
            )
        validateSelectiveNodeResolvers(schema, setOf("Group"))
    }
}
