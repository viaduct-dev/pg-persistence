@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.graphql.PgGraphqlExecutor
import dev.viaduct.persistence.runtime.graphql.PgGraphqlRequest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.Field
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DbLookupTest {
    @Test
    fun `scalar and typed reference lookups bind values without interpolation`() {
        val key = "a\"} malicious {"
        val scalar = DbLookup.by<String, LookupRow>(LookupRow.Fields.label)
        val reference = DbLookup.by(LookupRow.Fields.person)
        assertEquals(
            listOf("""{"label":{"eq":"a\"} malicious {"}}""", """{"personId":{"eq":"person-id"}}"""),
            listOf(
                scalar.filter(key).encoded().toString(),
                reference.filter(GlobalID(LookupPerson.Reflection, "person-id")).encoded().toString(),
            ),
        )
    }

    @Test
    fun `wrong GlobalID types fail before provider execution`() {
        @Suppress("UNCHECKED_CAST")
        val key = GlobalID(LookupRow.Reflection, "wrong") as GlobalID<LookupPerson>
        assertFailsWith<IllegalArgumentException> { DbLookup.by(LookupRow.Fields.person).filter(key) }
    }

    @Test
    fun `collection projection is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DbLookup
                .where<String, LookupRow>(LookupRow.Reflection) { PgGraphqlFilter.empty() }
                .project(LookupRow.Fields.people)
        }
    }

    @Test
    fun `projection cannot be chained`() {
        assertFailsWith<IllegalArgumentException> {
            DbLookup
                .by<String, LookupRow>(LookupRow.Fields.label)
                .project(LookupRow.Fields.person)
                .project(LookupPerson.Fields.manager)
        }
    }

    @Test
    fun `list traversal preserves duplicate references and refreshes authorization per page`() =
        runBlocking {
            val calls = mutableListOf<Pair<PgGraphqlRequest, Map<String, String>>>()
            var headerCount = 0
            val client =
                DbClient(
                    PgGraphqlExecutor { request, headers ->
                        calls += request to headers
                        response(
                            """[{"node":{"uuidId":"row-${calls.size}",
                              "_viaduct_lookup_id":"same-person"}}]""",
                            calls.size == 1,
                            "page-1",
                        )
                    },
                    DbRequestHeaders { mapOf("Authorization" to "request-${++headerCount}") },
                )
            val result =
                client.lookup(
                    context(),
                    DbLookup.by<String, LookupRow>(LookupRow.Fields.label).project(LookupRow.Fields.person),
                    "\"unsafe\"",
                )
            assertEquals(
                listOf("same-person", "same-person") to listOf("request-1", "request-2"),
                result.map { it.id } to calls.map { it.second.getValue("Authorization") },
            )
            check(calls.all { "unsafe" !in it.first.document && "unsafe" in it.first.variables.toString() })
            check("after:" in calls[1].first.document)
        }

    @ParameterizedTest
    @ValueSource(strings = ["empty", "missing-cursor", "repeated-cursor", "missing-reference"])
    fun `invalid provider pages fail without hanging`(case: String): Unit =
        runBlocking {
            val edges =
                when (case) {
                    "empty" -> "[]"
                    "missing-reference" -> """[{"node":{"uuidId":"row"}}]"""
                    else -> """[{"node":{"uuidId":"row","_viaduct_lookup_id":"person"}}]"""
                }
            val cursor = if (case == "missing-cursor") null else "same"
            val client = DbClient(PgGraphqlExecutor { _, _ -> response(edges, true, cursor) })
            assertFailsWith<Exception> {
                withTimeout(1000) {
                    client.lookup(
                        context(),
                        DbLookup.by<String, LookupRow>(LookupRow.Fields.label).project(LookupRow.Fields.person),
                        "label",
                    )
                }
            }.also { check(it !is CancellationException) }
        }

    @Test
    fun `empty matches return an empty list`() =
        runBlocking {
            val client = DbClient(PgGraphqlExecutor { _, _ -> response("[]", false, null) })
            val lookup = DbLookup.by<String, LookupRow>(LookupRow.Fields.label)
            assertEquals(emptyList(), client.lookup(context(), lookup, "absent"))
        }

    @Test
    fun `upstream errors use existing transport behavior`(): Unit =
        runBlocking {
            val client =
                DbClient(
                    PgGraphqlExecutor { _, _ ->
                        DbResult(null, listOf(UpstreamGraphqlError("permission denied")))
                    },
                )
            assertFailsWith<IllegalStateException> {
                client.lookup(context(), DbLookup.by<String, LookupRow>(LookupRow.Fields.label), "x")
            }
        }

    @Test
    fun `provider cancellation propagates unchanged`() =
        runBlocking {
            val cancellation = CancellationException("cancelled")
            val client = DbClient(PgGraphqlExecutor { _, _ -> throw cancellation })
            assertSame(
                cancellation,
                assertFailsWith<CancellationException> {
                    client.lookup(context(), DbLookup.by<String, LookupRow>(LookupRow.Fields.label), "x")
                },
            )
        }

    private fun context(): ResolverExecutionContext<Query> =
        mockk<ResolverExecutionContext<Query>>().also { ctx ->
            every { ctx.globalIDFor(LookupPerson.Reflection, any()) } answers {
                GlobalID(LookupPerson.Reflection, secondArg())
            }
            every { ctx.ref(any<GlobalID<LookupPerson>>()) } answers {
                LookupPerson(firstArg<GlobalID<LookupPerson>>().internalID)
            }
        }

    private fun response(
        edges: String,
        hasNext: Boolean,
        cursor: String?,
    ): DbResult<kotlinx.serialization.json.JsonObject> =
        DbResult(
            Json
                .parseToJsonElement(
                    """{"lookupRowCollection":{"edges":$edges,"pageInfo":{"hasNextPage":$hasNext,"endCursor":${cursor?.let {
                        "\"$it\""
                    } ?: "null"}}}}""",
                ).jsonObject,
        )
}

class LookupPerson(
    val id: String,
) : NodeObject {
    object Reflection : viaduct.api.reflect.Type<LookupPerson> by MutationFixtureType(LookupPerson::class)

    object Fields {
        val manager = MutationFixtureField("manager", Reflection, Reflection)
    }
}

class LookupRow : NodeObject {
    object Reflection : viaduct.api.reflect.Type<LookupRow> by MutationFixtureType(LookupRow::class)

    object Fields {
        val label =
            object : Field<LookupRow> {
                override val name = "label"
                override val containingType = Reflection
            }
        val person = MutationFixtureField("person", Reflection, LookupPerson.Reflection)
        val people = MutationFixtureField("people", Reflection, LookupPerson.Reflection)
    }

    class Builder {
        @Suppress("UNUSED_PARAMETER")
        fun people(value: List<LookupPerson>) = this
    }
}
