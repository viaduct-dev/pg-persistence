@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslationSchema
import dev.viaduct.persistence.pggraphql.translation.abstractAlias
import dev.viaduct.persistence.runtime.graphql.GraphqlQuery
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import dev.viaduct.persistence.runtime.node.NodeReferenceHydrator
import dev.viaduct.persistence.runtime.node.NodeReferencePlanner
import dev.viaduct.persistence.runtime.node.NodeReferenceSelection
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.select.OutputSelectionFragment
import viaduct.api.select.SelectionSet
import viaduct.api.types.Query
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DbFetcherReferenceTest {
    @Test
    fun `reference reads restore abstract error paths before throwing`() =
        withFixture { fixture ->
            runBlocking {
                val failure =
                    assertFailsWith<UpstreamGraphqlException> {
                        val subjectAlias = abstractAlias("subject", "AbstractPerson")
                        fixture.fetch(
                            """{"data":{"activity":null},"errors":[{"message":"failed",
                    "path":["activity","edges",0,"node","$subjectAlias","name"]}]}""",
                        )
                    }
                assertEquals(
                    listOf("activity", "subject", "name"),
                    failure.errors
                        .single()
                        .path
                        .map { it.jsonPrimitive.content },
                )
            }
        }

    @Test
    fun `reference reads validate owned semantic non-null fields`() =
        withFixture { fixture ->
            runBlocking {
                every { SemanticNotNullCoordinates.load(any()) } returns setOf("AbstractActivity.title")
                val failure =
                    assertFailsWith<UpstreamGraphqlException> {
                        fixture.fetch("""{"data":{"activity":{"edges":[{"node":{"title":null}}]}}}""")
                    }
                assertEquals(
                    "SEMANTIC_NON_NULL_VIOLATION",
                    failure.errors
                        .single()
                        .extensions["code"]
                        ?.jsonPrimitive
                        ?.content,
                )
            }
        }

    @Test
    fun `empty owned selections still fetch and hydrate references`() =
        withFixture { fixture ->
            runBlocking {
                every { fixture.owned.isEmpty() } returns true
                val actual = fixture.fetch("""{"data":{"activity":{"edges":[{"node":{"subject":null}}]}}}""")
                assertSame(fixture.node, actual)
                assertEquals(1, fixture.requests)
            }
        }

    @Test
    fun `reference reads retain the explicit concrete type`() =
        withFixture { fixture ->
            runBlocking {
                fixture.fetch("""{"data":{"activity":{"edges":[{"node":{"title":"Activity"}}]}}}""")
                verify {
                    fixture.planner.plan(
                        fixture.read.root,
                        fixture.owned,
                        listOf("subject { __typename }"),
                        AbstractActivity.Reflection,
                    )
                }
            }
        }

    @Test
    fun `hydration receives the restored unwrapped response`() =
        withFixture { fixture ->
            runBlocking {
                fixture.fetch(
                    """{"data":{"activity":{"edges":[{"node":{
                    "title":"Activity","${abstractAlias("subject", "AbstractPerson")}":{"__typename":"AbstractPerson"}
                }}]}}}""",
                )
                val expected =
                    Json.parseToJsonElement(
                        """{"title":"Activity","subject":{"__typename":"AbstractPerson"}}""",
                    ) as JsonObject
                verify {
                    fixture.hydrator.hydrate(
                        expected,
                        fixture.owned,
                        listOf(fixture.reference),
                        fixture.context,
                    )
                }
            }
        }

    private fun withFixture(test: (ReferenceFetchFixture) -> Unit) {
        mockkObject(SemanticNotNullCoordinates)
        try {
            every { SemanticNotNullCoordinates.load(any()) } returns emptySet()
            test(ReferenceFetchFixture())
        } finally {
            unmockkObject(SemanticNotNullCoordinates)
        }
    }
}

private class ReferenceFetchFixture {
    val owned by lazy { mockk<SelectionSet<AbstractActivity>>() }
    private val requested by lazy { mockk<SelectionSet<AbstractActivity>>() }
    val planner by lazy { mockk<DbQueryPlanner>() }
    val hydrator by lazy { mockk<NodeReferenceHydrator>() }
    val reference by lazy { mockk<NodeReferenceSelection>() }
    val context by lazy { mockk<ResolverExecutionContext<Query>>() }
    val node = AbstractActivity()
    val read = DbRead(DbRoot("activity", singleViaFilteredCollection = true), AbstractActivity.Reflection)
    var requests = 0
        private set

    init {
        every { owned.isEmpty() } returns false
    }

    suspend fun fetch(response: String): AbstractActivity {
        val referencePlanner = mockk<NodeReferencePlanner>()
        val reflection = mockk<GeneratedTypeReflection>()
        every { owned.type } returns AbstractActivity.Reflection
        every { owned.toFragment() } returns
            OutputSelectionFragment(
                "Main",
                "fragment Main on AbstractActivity { title }",
                emptyMap(),
            )
        every { referencePlanner.plan(requested, owned) } returns listOf(reference)
        every { reference.upstreamSelection(reflection) } returns "subject { __typename }"
        every { reference.kind } returns dev.viaduct.persistence.runtime.node.NodeReferenceKind.ABSTRACT
        every { reflection.translationSchema(any()) } returns PgGraphqlTranslationSchema(emptyMap(), emptyMap())
        every { planner.plan(any(), any(), any(), any()) } returns
            GraphqlQuery(
                text = "query",
                variables = JsonObject(emptyMap()),
                responseKey = "activity",
            )
        every { hydrator.hydrate(any<JsonObject>(), owned, listOf(reference), context) } returns node
        HttpClient(
            MockEngine {
                requests++
                respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ).use { http ->
            val transport = PgGraphqlTransport(http, "https://example.test/graphql", DbRequestHeaders { emptyMap() })
            return DbFetcher(transport, planner, reflection, referencePlanner, hydrator)
                .fetchNode(context, read, owned, requested)
        }
    }
}
