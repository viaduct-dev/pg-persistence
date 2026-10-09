package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.gradle.PersistenceSchemaModelLoader
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class GrtDelegateGeneratorTest {
    @Test
    fun `generated entities and connections do not access Viaduct implementation APIs`() {
        withSchema(
            """
            type Person implements Node {
              id: ID!, username: String!, manager: Person, managerId: ID @idOf(type: "Person")
            }
            type PersonEdge @edge { cursor: String!, node: Person! }
            type People @connection { edges: [PersonEdge!]!, nodes: [Person!]!, pageInfo: PageInfo! }
            type PageInfo { hasNextPage: Boolean!, hasPreviousPage: Boolean!, startCursor: String, endCursor: String }
            """.trimIndent(),
        ) { directory ->
            val model = PersistenceSchemaModelLoader.build(directory, null, validatePgGraphqlFields = false)
            val output = directory.resolve("delegates")
            GrtDelegateGenerator().write(model, "example.grts", listOf(directory.resolve("Model.graphqls")), output)
            val source = output.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
            val forbidden =
                listOf(
                    "viaduct.api.internal",
                    "viaduct.engine",
                    "viaduct.tenant.runtime",
                    "InternalApi",
                    "__engineObject",
                )
            assertEquals(emptyList(), forbidden.filter { it in source })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["data: [JSON]", "value: String", "time: Time", "times: [Time]"])
    fun `unproven scalar shapes fail explicitly`(field: String) {
        withSchema("type Person implements Node { id: ID!, $field }") { directory ->
            val model = PersistenceSchemaModelLoader.build(directory, null, validatePgGraphqlFields = false)
            val failure =
                runCatching {
                    GrtDelegateGenerator().write(
                        model,
                        "example.grts",
                        listOf(directory.resolve("Model.graphqls")),
                        directory.resolve("delegates"),
                    )
                }.exceptionOrNull()
            assertEquals(true, failure is IllegalArgumentException)
        }
    }

    @Test
    fun `generation respects type extensions and ignores resolver only fields`() {
        withSchema(
            """
            type Person implements Node { id: ID!, username: String!, display: String @resolver }
            extend type Person { nickname: String }
            """.trimIndent(),
        ) { directory ->
            val model = PersistenceSchemaModelLoader.build(directory, null, validatePgGraphqlFields = false)
            val output = directory.resolve("delegates")
            GrtDelegateGenerator().write(model, "example.grts", listOf(directory.resolve("Model.graphqls")), output)
            val source = output.resolve("example/grts/persistence/PersonEntity.kt").readText()
            val fields = listOf("Person.Fields.`nickname`" in source, "Person.Fields.`display`" in source)
            assertEquals(listOf(true, false), fields)
        }
    }

    private fun withSchema(
        sdl: String,
        test: (java.io.File) -> Unit,
    ) {
        val directory = Files.createTempDirectory("grt-delegate-generator-").toFile()
        try {
            directory.resolve("Model.graphqls").writeText("interface Node { id: ID! }\n$sdl")
            test(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
