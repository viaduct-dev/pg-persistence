package dev.viaduct.persistence.pggraphql.translation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AbstractResponseRestorerTest {
    @Test
    fun `internal abstract list page retains its cursor envelope`() {
        val responseKey = ABSTRACT_LIST_PAGE_PREFIX + "subjects"
        val person = """{"__typename":"Person","uuidId":"person-1"}"""
        val row = """{"${abstractAlias("node", "Person")}":$person}"""

        assertEquals(
            Json.parseToJsonElement(
                """
                {"$responseKey":{
                  "edges":[{"node":$person}],
                  "pageInfo":{"hasNextPage":true,"endCursor":"next"}
                }}
                """.trimIndent(),
            ),
            restore(
                """
                {"$ABSTRACT_LIST_PREFIX$responseKey":{
                  "edges":[{"node":$row}],
                  "pageInfo":{"hasNextPage":true,"endCursor":"next"}
                }}
                """,
            ),
        )
    }

    @Test
    fun `specific alias transformers run before the concrete target transformer`() {
        val person = """{"__typename":"Person","name":"Ada"}"""
        val row = """{"${abstractAlias("node", "Person")}":$person}"""
        assertEquals(
            Json.parseToJsonElement(
                """{"nodes":[$person],"list":[$person],"kind":"SubjectEdge","subject":$person,"label":"member"}""",
            ),
            restore(
                """
                {
                    "${ABSTRACT_NODES_PREFIX}nodes":[{"node":$row}],
                    "${ABSTRACT_LIST_PREFIX}list":{"edges":[{"node":$row}]},
                    "${typeAlias("kind", "SubjectEdge")}":"StorageEdge",
                    "${abstractAlias("subject", "Person")}":$person,
                    "${abstractAlias("subject", "Group")}":null,
                    "label":"member"
                }
                """,
            ),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = [ABSTRACT_NODES_PREFIX, ABSTRACT_LIST_PREFIX])
    fun `null collections remain null`(prefix: String) {
        assertEquals(
            Json.parseToJsonElement("""{"subjects":null}"""),
            restore("""{"${prefix}subjects":null}"""),
        )
    }

    @Test
    fun `null typename remains null`() {
        assertEquals(
            Json.parseToJsonElement("""{"kind":null}"""),
            restore("""{"${typeAlias("kind", "SubjectEdge")}":null}"""),
        )
    }

    @Test
    fun `all null concrete targets produce a single null field`() {
        assertEquals(
            Json.parseToJsonElement("""{"subject":null}"""),
            restore(
                """{"${abstractAlias("subject", "Person")}":null,"${abstractAlias("subject", "Group")}":null}""",
            ),
        )
    }

    @Test
    fun `a concrete target must have the expected typename`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                restore("""{"${abstractAlias("subject", "Person")}":{"__typename":"Group"}}""")
            }
        assertEquals("Abstract field subject expected concrete type Person, got \"Group\"", failure.message)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `target collisions fail regardless of response field order`(targetFirst: Boolean) {
        val target = """ "${abstractAlias("subject", "Person")}":null """
        val plain = """ "subject":null """
        val fields = if (targetFirst) "$target,$plain" else "$plain,$target"
        val failure = assertFailsWith<IllegalArgumentException> { restore("{$fields}") }
        assertEquals("Abstract response field subject collides with another selected field", failure.message)
    }

    @Test
    fun `nested concrete references are restored before the parent is validated`() {
        assertEquals(
            Json.parseToJsonElement(
                """{"subject":{"__typename":"Person","favorite":{"__typename":"Group","name":"Friends"}}}""",
            ),
            restore(
                """
                {"${abstractAlias("subject", "Person")}":{
                    "__typename":"Person",
                    "${abstractAlias("favorite", "Group")}":{"__typename":"Group","name":"Friends"}
                }}
                """,
            ),
        )
    }

    private fun restore(source: String): JsonElement = ResponseShapeRestorer().restore(Json.parseToJsonElement(source))
}
