@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.postgresql.ApprovalRequestFixture.Companion.withFixture
import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.DbResult
import dev.viaduct.persistence.runtime.db.PgGraphqlFilter
import dev.viaduct.persistence.runtime.db.PgGraphqlObject
import dev.viaduct.persistence.runtime.db.PgGraphqlOrder
import dev.viaduct.persistence.runtime.db.PgGraphqlOrderDirection
import dev.viaduct.persistence.runtime.graphql.PgGraphqlExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.api.internal.ObjectBase
import viaduct.api.types.OffsetCursor
import viaduct.engine.api.EngineObjectDataBuilder
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.runFeatureTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Modern field resolver boundaries must preserve separate pages, cursors, and edge metadata. */
class ModernConnectionExecutionIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = ["queue", "reviewQueue"])
    fun `aliases request independent pages of the same modern connection`(field: String) =
        withFixture(modernConnections = true) { f ->
            runBlocking {
                populate(f, field)
                engine(f, field).runFeatureTest {
                    runQuery(
                        """{ review(id: "${f.assignmentId}") {
                          one: $field(first: 1) { edges { label node { __typename } } }
                          two: $field(first: 2) { edges { label node { __typename } } }
                          tail: $field(last: 1) { edges { label node { __typename } } }
                        } }""",
                    ).assertJson(
                        """{"data":{"review":{
                          "one":{"edges":[{"label":"first","node":{"__typename":"AccessRequest${f.suffix}"}}]},
                          "two":{"edges":[
                            {"label":"first","node":{"__typename":"AccessRequest${f.suffix}"}},
                            {"label":"second","node":{"__typename":"ImportRequest${f.suffix}"}}
                          ]},
                          "tail":{"edges":[{"label":"second","node":{"__typename":"ImportRequest${f.suffix}"}}]}
                        }}}""",
                    )
                }
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["queue", "reviewQueue"])
    fun `modern connections round trip Viaduct offset cursors in both directions`(field: String) =
        withFixture(modernConnections = true) { f ->
            runBlocking {
                populate(f, field)
                engine(f, field).runFeatureTest {
                    fun page(arguments: String): Map<String, Any?> {
                        val result =
                            runQuery(
                                """{ review(id: "${f.assignmentId}") {
                          $field($arguments) { edges { cursor label } }
                        } }""",
                            )

                        @Suppress("UNCHECKED_CAST")
                        val review =
                            requireNotNull(result.getData<Map<String, Any?>>()).getValue("review") as Map<String, Any?>

                        @Suppress("UNCHECKED_CAST")
                        val connection = review.getValue(field) as Map<String, Any?>
                        @Suppress("UNCHECKED_CAST")
                        return (connection.getValue("edges") as List<Map<String, Any?>>).single()
                    }
                    val first = page("first: 1")
                    val after = first.getValue("cursor") as String
                    val second = page("first: 1, after: ${JsonPrimitive(after)}")
                    val before = second.getValue("cursor") as String
                    val previous = page("last: 1, before: ${JsonPrimitive(before)}")
                    assertEquals(
                        listOf(
                            "first" to
                                viaduct.api.types.OffsetCursor
                                    .fromOffset(0)
                                    .value,
                            "second" to
                                viaduct.api.types.OffsetCursor
                                    .fromOffset(1)
                                    .value,
                            "first" to
                                viaduct.api.types.OffsetCursor
                                    .fromOffset(0)
                                    .value,
                        ),
                        listOf(first, second, previous).map { it["label"] to it["cursor"] },
                    )
                }
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["default", "before-zero", "after-end", "before-end", "large-last", "empty", "empty-last"])
    fun `page boundaries use Viaduct builder semantics`(case: String) =
        withFixture(modernConnections = true) { f ->
            runBlocking {
                if (!case.startsWith("empty")) {
                    populate(f, "queue")
                } else {
                    f.assign(f.createRequest("AccessRequest", "requestedPermission", "EDITOR"))
                }
                val arguments =
                    when (case) {
                        "before-zero" -> "(last: 1, before: ${JsonPrimitive(OffsetCursor.fromOffset(0).value)})"
                        "after-end" -> "(first: 1, after: ${JsonPrimitive(OffsetCursor.fromOffset(99).value)})"
                        "before-end" -> "(last: 1, before: ${JsonPrimitive(OffsetCursor.fromOffset(100).value)})"
                        "large-last" -> "(last: 20)"
                        "empty-last" -> "(last: 1)"
                        else -> ""
                    }
                val offsets = if (case in setOf("default", "large-last")) listOf(0, 1) else emptyList()
                engine(f, "queue").runFeatureTest {
                    runQuery(
                        """{ review(id: "${f.assignmentId}") {
                  queue$arguments { edges { cursor label } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } }
                } }""",
                    ).assertJson(
                        expectedPage("queue", offsets, case == "before-zero", case in setOf("after-end", "before-end")),
                    )
                }
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["first: 35", "last: 35", "last: 5", "default"])
    fun `provider row caps do not truncate modern pages or counts`(arguments: String) =
        withFixture(modernConnections = true) { f ->
            runBlocking {
                val access = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                f.assign(access)
                repeat(35) { index ->
                    f.addTo(
                        "queue",
                        access,
                        PgGraphqlObject.of(
                            "uuidId" to UUID(0, index.toLong() + 1).toString(),
                            "label" to index.toString(),
                        ),
                    )
                }
                val offsets =
                    when (arguments) {
                        "last: 5" -> (30..34).toList()
                        "default" -> (0..19).toList()
                        else -> (0..34).toList()
                    }
                val input = if (arguments == "default") "" else "($arguments)"
                engine(f, "queue").runFeatureTest {
                    runQuery(
                        """{ review(id: "${f.assignmentId}") {
                  queue$input { edges { cursor label } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } }
                } }""",
                    ).assertJson(
                        expectedPage(
                            "queue",
                            offsets,
                            arguments == "default",
                            arguments == "last: 5",
                            numericLabels = true,
                        ),
                    )
                }
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["first", "last"])
    fun `filters apply to the slice and to the tail count`(direction: String) =
        withFixture(modernConnections = true) { f ->
            runBlocking {
                populate(f, "queue")
                val result =
                    f.readConnection(
                        "queue",
                        "edges { cursor label }",
                        mapOf(direction to 1),
                        filter = PgGraphqlFilter.eq("label", "second"),
                        orderBy = listOf(PgGraphqlOrder("uuidId", PgGraphqlOrderDirection.DESC_NULLS_LAST)),
                    )
                val edge = result.get<List<ObjectBase>>("edges", f.reflection("ApprovalEdge${f.suffix}").kcls).single()
                assertEquals(
                    "second" to OffsetCursor.fromOffset(0).value,
                    edge.get<String>("label", String::class) to edge.get<String>("cursor", String::class),
                )
            }
        }

    @Test
    fun `root collection returns a generated modern connection with node references`() =
        withFixture(modernConnections = true) { f ->
            runBlocking {
                val target = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                val connection =
                    f.readRootConnection(
                        "edges { cursor node { id } }",
                        mapOf("first" to 1),
                        PgGraphqlFilter.eq("uuidId", target.internalID),
                    )
                val edge =
                    connection
                        .get<List<ObjectBase>>("edges", f.reflection("AccessEdge${f.suffix}").kcls)
                        .single()
                val node = edge.get<ObjectBase>("node", f.reflection("AccessRequest${f.suffix}").kcls)
                assertEquals(
                    "AccessRequest${f.suffix}" to OffsetCursor.fromOffset(0).value,
                    node::class.simpleName to edge.get<String>("cursor", String::class),
                )
            }
        }

    @Test
    fun `typename-only connections require no provider request`() =
        withFixture(modernConnections = true) { f ->
            runBlocking {
                val client = DbClient(PgGraphqlExecutor { _, _ -> error("Unexpected database request") })
                val connection = f.readRootConnection("__typename", connectionClient = client)
                assertEquals("AccessConnection${f.suffix}", connection::class.simpleName)
            }
        }

    @Test
    fun `compatibility nodes use the same IDs and independent references as modern edges`() =
        withFixture(modernConnections = true) { f ->
            runBlocking {
                val target = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                val result =
                    f.readRootConnection(
                        "nodes { id } edges { node { id } }",
                        mapOf("first" to 1),
                        PgGraphqlFilter.eq("uuidId", target.internalID),
                    )
                val edges = result.get<List<ObjectBase>>("edges", f.reflection("AccessEdge${f.suffix}").kcls)
                val nodes = result.get<List<ObjectBase>>("nodes", target.type.kcls)
                val edgeNodes = edges.map { it.get<ObjectBase>("node", target.type.kcls) }
                assertEquals(
                    edgeNodes.map { it.internalId() } to listOf(false),
                    nodes.map { it.internalId() } to
                        nodes.zip(edgeNodes).map { (node, edgeNode) ->
                            node.__engineObject === edgeNode.__engineObject
                        },
                )
            }
        }

    @Test
    fun `requested modern aliases stay with their connection field resolver`() =
        withFixture(modernConnections = true) { f ->
            runBlocking {
                populate(f, "queue")
                val result =
                    f.readNodeOwner(
                        "id one: queue(first: 1) { edges { label } } two: queue(first: 2) { edges { label } }",
                        ownedFields = "id",
                    )
                val data = result.__engineObject as viaduct.engine.api.EngineObjectData.Sync
                assertEquals(null, data.getOrNull("queue"))
            }
        }

    @Test
    fun `modern connection cannot be read through the generic fetch API`() =
        withFixture(modernConnections = true) { f ->
            runBlocking {
                assertFailsWith<IllegalArgumentException> { f.readOwner("queue { edges { label } }") }
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["queue(first: 1) { edges { label } }", "queue { edges { cursor } }"])
    fun `legacy structural connection cannot expose another paging API`(selection: String) =
        withFixture { f ->
            runBlocking {
                assertFailsWith<IllegalArgumentException> { f.readOwner(selection) }
            }
        }

    @Test
    fun `unpaged node hydration never leaks a provider cursor`() =
        withFixture { f ->
            runBlocking {
                populate(f, "queue")
                val owner = f.readNodeOwner("queue { edges { label node { __typename } } }")
                val connection = owner.get<ObjectBase>("queue", f.field("queue").type.kcls)
                val edges = connection.get<List<ObjectBase>>("edges", f.reflection("ApprovalEdge${f.suffix}").kcls)
                assertEquals(
                    listOf(OffsetCursor.fromOffset(0).value, OffsetCursor.fromOffset(1).value),
                    edges.map { it.get<String>("cursor", String::class) },
                )
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["empty", "missing-cursor", "repeated-cursor"])
    fun `count traversal fails instead of hanging on invalid provider metadata`(case: String) =
        withFixture(modernConnections = true) { f ->
            runBlocking {
                val edges = if (case == "empty") "[]" else """[{"cursor":"native-cursor"}]"""
                val cursor = if (case == "missing-cursor") "null" else "\"native-cursor\""
                val client =
                    DbClient(
                        PgGraphqlExecutor { _, _ ->
                            DbResult(
                                Json
                                    .parseToJsonElement(
                                        """{"${f.assignment.collectionField}":{"edges":[{"node":{
                  "queue":{"edges":$edges,"pageInfo":{"hasNextPage":true,"endCursor":$cursor}}
                }}]}}""",
                                    ).jsonObject,
                            )
                        },
                    )
                assertFailsWith<IllegalStateException> {
                    withTimeout(1000) { f.readConnection("queue", "edges { cursor }", mapOf("last" to 1), client) }
                }
            }
        }

    private fun expectedPage(
        field: String,
        offsets: List<Int>,
        hasNextPage: Boolean,
        hasPreviousPage: Boolean,
        numericLabels: Boolean = false,
    ): String =
        buildJsonObject {
            put(
                "data",
                buildJsonObject {
                    put(
                        "review",
                        buildJsonObject {
                            put(
                                field,
                                buildJsonObject {
                                    put(
                                        "edges",
                                        buildJsonArray {
                                            offsets.forEach { offset ->
                                                add(
                                                    buildJsonObject {
                                                        put("cursor", OffsetCursor.fromOffset(offset).value)
                                                        put(
                                                            "label",
                                                            if (numericLabels) {
                                                                offset.toString()
                                                            } else if (offset == 0) {
                                                                "first"
                                                            } else {
                                                                "second"
                                                            },
                                                        )
                                                    },
                                                )
                                            }
                                        },
                                    )
                                    put(
                                        "pageInfo",
                                        buildJsonObject {
                                            put("hasNextPage", hasNextPage)
                                            put("hasPreviousPage", hasPreviousPage)
                                            put(
                                                "startCursor",
                                                offsets.firstOrNull().cursorValue()
                                                    ?: kotlinx.serialization.json.JsonNull,
                                            )
                                            put(
                                                "endCursor",
                                                offsets.lastOrNull().cursorValue()
                                                    ?: kotlinx.serialization.json.JsonNull,
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }.toString()

    private fun Int?.cursorValue() = this?.let { JsonPrimitive(OffsetCursor.fromOffset(it).value) }

    private suspend fun populate(
        f: ApprovalRequestFixture,
        field: String,
    ) {
        val access = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
        val imported = f.createRequest("ImportRequest", "teamName", "Engineering")
        f.assign(access)
        f.addTo(field, access, PgGraphqlObject.of("uuidId" to ROW_ONE, "label" to "first"))
        f.addTo(field, imported, PgGraphqlObject.of("uuidId" to ROW_TWO, "label" to "second"))
    }

    private fun engine(
        f: ApprovalRequestFixture,
        field: String,
    ) = EngineTestModule(f.schema) {
        field("Query" to "review") {
            resolver {
                fn { _, _, _, _, _ ->
                    EngineObjectDataBuilder
                        .from(requireNotNull(schema.schema.getObjectType(f.assignment.typeName)))
                        .put("id", f.assignmentId)
                        .build()
                }
            }
        }
        field(f.assignment.typeName to field) {
            resolverExecutor {
                MockFieldUnbatchedResolverExecutor(
                    isSelective = true,
                    resolverId = field,
                ) { args, _, _, selections, _ ->
                    val requested = requireNotNull(selections).printAsFieldSet()
                    f
                        .readConnection(field, requested, args)
                        .__engineObject
                }
            }
        }
    }
}
