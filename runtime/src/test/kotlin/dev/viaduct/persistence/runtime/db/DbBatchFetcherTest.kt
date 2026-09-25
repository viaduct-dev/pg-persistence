@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslationSchema
import dev.viaduct.persistence.runtime.graphql.PgGraphqlExecutor
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import dev.viaduct.persistence.runtime.node.NodeReferenceHydrator
import dev.viaduct.persistence.runtime.node.NodeReferencePlanner
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.select.OutputSelectionFragment
import viaduct.api.select.SelectionSet
import viaduct.errors.ErroneousFieldException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DbBatchFetcherTest {
    @Test
    fun `reads all requested nodes beyond the provider row limit`() =
        runBlocking {
            val ids = (1..65).map { "node-$it" }
            val fixture = BatchFetchFixture(ids)

            val result = fixture.fetch(ids)

            assertEquals(ids.toSet(), result.mapValues { it.value.get() }.keys)
            assertEquals(listOf(65, 35, 5), fixture.requests.map(List<String>::size))
        }

    @Test
    fun `honors a provider row limit smaller than its default`() =
        runBlocking {
            val fixture = BatchFetchFixture(listOf("one", "two", "three"), limit = 1)

            val result = fixture.fetch(listOf("one", "two", "three"))

            result.values.forEach { it.get() }
            assertEquals(
                listOf(listOf("one", "two", "three"), listOf("two", "three"), listOf("three")),
                fixture.requests,
            )
        }

    @Test
    fun `only missing nodes fail after every available row has been read`() =
        runBlocking {
            val fixture = BatchFetchFixture(listOf("one", "two"), limit = 1)

            val result = fixture.fetch(listOf("one", "missing", "two", "one"))

            result.getValue("one").get()
            result.getValue("two").get()
            val failure = assertFailsWith<ErroneousFieldException> { result.getValue("missing").get() }
            assertEquals("MISSING_ROW", failure.fieldErrors.single().extensions["code"])
            assertEquals(3, result.size)
        }

    @Test
    fun `later page field errors remain attached to their own node`() =
        runBlocking {
            val fixture = BatchFetchFixture(listOf("one", "two", "three"), limit = 1, errorId = "two")

            val result = fixture.fetch(listOf("one", "two", "three"))

            result.getValue("one").get()
            result.getValue("three").get()
            val failure = assertFailsWith<ErroneousFieldException> { result.getValue("two").get() }
            assertEquals("Could not read title", failure.fieldErrors.single().message)
        }

    @Test
    fun `cancellation during a later request is not converted into missing rows`() =
        runBlocking<Unit> {
            val fixture =
                BatchFetchFixture(listOf("one", "two"), limit = 1) { request ->
                    if (request == 2) throw CancellationException("cancelled")
                }

            assertFailsWith<CancellationException> { fixture.fetch(listOf("one", "two")) }
        }

    @Test
    fun `empty input performs no requests`() =
        runBlocking {
            val fixture = BatchFetchFixture(emptyList())

            assertEquals(emptyMap(), fixture.fetch(emptyList()))
            assertEquals(emptyList(), fixture.requests)
        }
}

private class BatchFetchFixture(
    private val existingIds: List<String>,
    private val limit: Int = 30,
    private val errorId: String? = null,
    private val beforePage: (Int) -> Unit = {},
) {
    val requests = mutableListOf<List<String>>()
    private val context by lazy { mockk<SelectiveNodeExecutionContext<AbstractActivity>>() }
    private val selections by lazy { mockk<SelectionSet<AbstractActivity>>() }
    private val reflection by lazy { mockk<GeneratedTypeReflection>() }
    private val referencePlanner by lazy { mockk<NodeReferencePlanner>() }
    private val hydrator by lazy { mockk<NodeReferenceHydrator>() }

    init {
        every { context.ownedSelections() } returns selections
        every { selections.type } returns AbstractActivity.Reflection
        every { selections.toFragment() } returns
            OutputSelectionFragment(
                "Main",
                "fragment Main on AbstractActivity { title }",
                emptyMap(),
            )
        every { reflection.translationSchema(any()) } returns PgGraphqlTranslationSchema(emptyMap(), emptyMap())
        every { referencePlanner.plan(selections) } returns emptyList()
        every { hydrator.hydrate(any<JsonObject>(), selections, emptyList(), context) } returns AbstractActivity()
    }

    private val executor =
        PgGraphqlExecutor { request, _ ->
            val ids =
                request.variables
                    .getValue("ids")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            requests += ids
            beforePage(requests.size)
            val returned = existingIds.filter { it in ids }.take(limit)
            val errors =
                returned.mapIndexedNotNull { index, id ->
                    if (id != errorId) {
                        null
                    } else {
                        UpstreamGraphqlError(
                            "Could not read title",
                            path =
                                listOf(
                                    JsonPrimitive("activityCollection"),
                                    JsonPrimitive("edges"),
                                    JsonPrimitive(index),
                                    JsonPrimitive("node"),
                                    JsonPrimitive("title"),
                                ),
                        )
                    }
                }
            DbResult(
                buildJsonObject {
                    put(
                        "activityCollection",
                        buildJsonObject {
                            put(
                                "edges",
                                JsonArray(
                                    returned.map { id ->
                                        buildJsonObject {
                                            put(
                                                "node",
                                                buildJsonObject {
                                                    put("uuidId", id)
                                                    put("title", id)
                                                },
                                            )
                                        }
                                    },
                                ),
                            )
                        },
                    )
                },
                errors,
            )
        }

    suspend fun fetch(ids: List<String>) =
        DbBatchFetcher(
            PgGraphqlTransport(executor, DbRequestHeaders { emptyMap() }),
            DbQueryPlanner(reflection),
            reflection,
            referencePlanner,
            hydrator,
        ).fetchByInternalIdsResult(context, "activityCollection", ids, selections)
}
