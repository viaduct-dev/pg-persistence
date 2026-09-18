package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.pggraphql.translation.ABSTRACT_LIST_PAGE_PREFIX
import dev.viaduct.persistence.runtime.node.NodeListPager
import dev.viaduct.persistence.runtime.node.NodeReferenceKind
import dev.viaduct.persistence.runtime.node.NodeReferenceSelection
import dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NodeListPagerTest {
    private val reference =
        NodeReferenceSelection("records", MutationRecord.Reflection, NodeReferenceKind.LIST, MutationRecord.Reflection)

    @Test
    fun `follows every abstract list page and returns the complete mixed list`() =
        runBlocking<Unit> {
            val relationship =
                requireNotNull(
                    AbstractTypeMappings
                        .load(javaClass.classLoader)
                        .relationship("AbstractActivity", "subjects"),
                )
            val abstractReference =
                NodeReferenceSelection(
                    "subjects",
                    AbstractSubject.Reflection,
                    NodeReferenceKind.ABSTRACT,
                    AbstractSubject.Reflection,
                    abstractRelationship = relationship,
                )
            val remaining = ArrayDeque(listOf(abstractPage("AbstractGroup", false, "b")))
            val requests = mutableListOf<String>()

            val result =
                NodeListPager.complete(
                    abstractPage("AbstractPerson", true, "a"),
                    listOf(abstractReference),
                ) {
                    requests.add(it)
                    remaining.removeFirst()
                }

            assertEquals(
                listOf("AbstractPerson", "AbstractGroup"),
                result.getValue("subjects").jsonArray.map {
                    it.jsonObject
                        .getValue("__typename")
                        .jsonPrimitive.content
                },
            )
            assertEquals(listOf(abstractReference.listSelection("a")), requests)
        }

    @Test
    fun `follows every page in provider order`() =
        runBlocking<Unit> {
            val remaining = ArrayDeque(listOf(page("second", true, "b"), page("third", false)))
            val requests = mutableListOf<String>()
            val result =
                NodeListPager.complete(page("first", true, "a"), listOf(reference)) {
                    requests.add(it)
                    remaining.removeFirst()
                }
            assertEquals(
                listOf("first", "second", "third"),
                result.getValue("records").jsonObject.getValue("edges").jsonArray.map {
                    it.jsonObject
                        .getValue("node")
                        .jsonObject
                        .getValue("uuidId")
                        .jsonPrimitive.content
                },
            )
            assertEquals(listOf(reference.listSelection("a"), reference.listSelection("b")), requests)
        }

    @Test
    fun `a complete list requires no further requests`() =
        runBlocking<Unit> {
            val initial = page("only", false)
            assertEquals(initial, NodeListPager.complete(initial, listOf(reference)) { error("Unexpected request") })
        }

    @Test
    fun `explicit connections are not automatically paged`() =
        runBlocking<Unit> {
            val initial = page("first", true, "a")
            assertEquals(
                initial,
                NodeListPager.complete(initial, listOf(reference.copy(kind = NodeReferenceKind.CONNECTION))) {
                    error("Unexpected request")
                },
            )
        }

    @Test
    fun `a missing next cursor fails rather than silently truncating`() =
        runBlocking<Unit> {
            assertFailsWith<IllegalArgumentException> {
                NodeListPager.complete(page("first", true), listOf(reference)) { error("Unexpected request") }
            }
        }

    @Test
    fun `a repeated cursor fails rather than looping forever`() =
        runBlocking<Unit> {
            assertFailsWith<IllegalStateException> {
                NodeListPager.complete(page("first", true, "a"), listOf(reference)) { page("second", true, "a") }
            }
        }

    @Test
    fun `upstream failures are not converted to truncated successes`() =
        runBlocking<Unit> {
            assertFailsWith<UnsupportedOperationException> {
                NodeListPager.complete(page("first", true, "a"), listOf(reference)) {
                    throw UnsupportedOperationException("upstream")
                }
            }
        }

    private fun page(
        id: String,
        hasNext: Boolean,
        cursor: String? = null,
    ) = Json
        .parseToJsonElement(
            """{"records":{"edges":[{"node":{"uuidId":"$id"}}],"pageInfo":{"hasNextPage":$hasNext,"endCursor":${cursor?.let {
                "\"$it\""
            } ?: "null"}}}}""",
        ).jsonObject

    private fun abstractPage(
        type: String,
        hasNext: Boolean,
        cursor: String,
    ): kotlinx.serialization.json.JsonObject {
        val responseKey = ABSTRACT_LIST_PAGE_PREFIX + "subjects"
        return Json
            .parseToJsonElement(
                """
                {
                  "$responseKey": {
                    "edges": [{"node": {"__typename": "$type", "uuidId": "$type"}}],
                    "pageInfo": {"hasNextPage": $hasNext, "endCursor": "$cursor"}
                  }
                }
                """.trimIndent(),
            ).jsonObject
    }
}
