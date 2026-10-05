package dev.viaduct.persistence.pggraphql.translation

import dev.viaduct.persistence.runtime.reflection.AbstractRelationship
import dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings
import graphql.language.AstPrinter
import graphql.parser.Parser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AbstractSelectionTranslatorTest {
    @Test
    fun `error on an association row maps to the public list item`() {
        val path = Json.parseToJsonElement("""["${ABSTRACT_LIST_PREFIX}subjects","edges",0,"node"]""").jsonArray
        assertEquals(
            Json.parseToJsonElement("""["subjects",0]""").jsonArray.toList(),
            ResponseShapeRestorer().restorePath(path),
        )
    }

    @Test
    fun `null mixed collection items are retained`() {
        val input =
            Json.parseToJsonElement(
                """
                {"${ABSTRACT_LIST_PREFIX}subjects":{"edges":[null,{"node":null}]}}
                """.trimIndent(),
            )
        assertEquals(Json.parseToJsonElement("""{"subjects":[null,null]}"""), ResponseShapeRestorer().restore(input))
    }

    @Test
    fun `nullable connection edges do not leak internal aliases`() {
        val input = Json.parseToJsonElement("""{"${VIADUCT_ASSOCIATION_EDGES_ALIAS_PREFIX}edges":null}""")
        assertEquals(Json.parseToJsonElement("""{"edges":null}"""), ResponseShapeRestorer().restore(input))
    }

    private val targets = setOf("Person", "Group")

    private fun translate(
        document: String,
        concreteType: String? = null,
    ): String = PgGraphqlTranslation.translateSelectionDocument(document, schema, concreteType = concreteType)

    private val mappings =
        AbstractTypeMappings(
            possibleTypes = mapOf("Subject" to targets, "Actor" to targets),
            relationships =
                listOf(
                    AbstractRelationship("Activity", "subject", "Subject", targets, true),
                    AbstractRelationship("Activity", "actor", "Actor", targets, true),
                    AbstractRelationship("Activity", "subjects", "Subject", targets, false, collection = true),
                ),
        )
    private val schema = PgGraphqlTranslationSchema(emptyMap(), emptyMap(), abstractTypes = mappings)

    @Test fun `union fragments are projected to concrete relationships`() {
        val result =
            translate(
                """
                fragment Main on Activity { subject { ... on Person { email } ... on Group { description } } }
                """.trimIndent(),
            )
        assertTrue(result.contains("subjectPerson{...{email}__typename}"), result)
        assertTrue(result.contains("subjectGroup{...{description}__typename}"), result)
    }

    @Test fun `interface shared fields and named fragments reach each implementation`() {
        val result =
            translate(
                """
                fragment Main on Activity { actor { ...Details } }
                fragment Details on Actor { name }
                """.trimIndent(),
            )
        assertEquals(2, Regex("\\bname\\b").findAll(result).count())
    }

    @Test fun `abstract root requires concrete collection type`() {
        assertFailsWith<IllegalArgumentException> {
            translate("fragment Main on Actor { name }")
        }
    }

    @Test fun `abstract root accepts explicit possible type`() {
        val result = translate("fragment Main on Actor { name }", "Person")
        assertTrue(result.contains("on Person"))
        assertFailsWith<IllegalArgumentException> {
            translate("fragment Main on Actor { name }", "Activity")
        }
    }

    @Test fun `abstract translation still rejects authored internal aliases`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                translate("fragment Main on Activity { _viaduct_nodes: name subject { __typename } }")
            }
        assertTrue(failure.message.orEmpty().contains("reserved alias"))
    }

    @Test fun `translation leaves the supplied parsed document unchanged`() {
        val document = Parser().parseDocument("fragment Main on Actor { name }")
        val original = AstPrinter.printAstCompact(document)
        PgGraphqlTranslation.translateSelectionDocument(document, schema, concreteType = "Person")
        assertEquals(original, AstPrinter.printAstCompact(document))
    }

    @Test fun `response restores aliased concrete union object`() {
        val input =
            Json.parseToJsonElement(
                """{
            "${abstractAlias("selected", "Person")}": {"__typename":"Person","name":"Ada"},
            "${abstractAlias("selected", "Group")}": null
        }""",
            )
        assertEquals(
            Json.parseToJsonElement("""{"selected":{"__typename":"Person","name":"Ada"}}"""),
            ResponseShapeRestorer().restore(input),
        )
    }

    @Test fun `multiple concrete references are not silently dropped`() {
        val input =
            Json.parseToJsonElement(
                """{
            "${abstractAlias("subject", "Person")}": {"__typename":"Person"},
            "${abstractAlias("subject", "Group")}": {"__typename":"Group"}
        }""",
            )
        assertFailsWith<IllegalArgumentException> { ResponseShapeRestorer().restore(input) }
    }

    @Test fun `mixed list restores objects and indexed error paths`() {
        val input =
            Json.parseToJsonElement(
                """{"${ABSTRACT_LIST_PREFIX}subjects":{"edges":[
            {"node":{"${abstractAlias(
                    "node",
                    "Person",
                )}":{"__typename":"Person"},"${abstractAlias("node", "Group")}":null}},
            {"node":{"${abstractAlias(
                    "node",
                    "Person",
                )}":null,"${abstractAlias("node", "Group")}":{"__typename":"Group"}}}
        ]}}""",
            )
        assertEquals(
            Json.parseToJsonElement("""{"subjects":[{"__typename":"Person"},{"__typename":"Group"}]}""").jsonObject,
            ResponseShapeRestorer().restore(input),
        )
        val path =
            listOf(
                "root",
                "${ABSTRACT_LIST_PREFIX}subjects",
                "edges",
                1,
                "node",
                abstractAlias("node", "Group"),
                "name",
            ).map {
                if (it is Int) {
                    kotlinx.serialization.json.JsonPrimitive(
                        it,
                    )
                } else {
                    kotlinx.serialization.json.JsonPrimitive(it.toString())
                }
            }
        assertEquals(
            listOf("root", "subjects", "1", "name"),
            ResponseShapeRestorer().restorePath(path).map {
                it.toString().trim('"')
            },
        )
    }
}
