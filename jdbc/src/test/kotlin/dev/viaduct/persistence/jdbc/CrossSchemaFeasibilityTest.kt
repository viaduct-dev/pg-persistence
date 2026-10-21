@file:Suppress("InjectDispatcher") // Real blocking JDBC concurrency requires IO worker threads.

package dev.viaduct.persistence.jdbc

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.viaduct.persistence.runtime.graphql.HttpPgGraphqlExecutor
import dev.viaduct.persistence.runtime.graphql.PgGraphqlExecutor
import dev.viaduct.persistence.runtime.graphql.PgGraphqlRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The transport feasibility gate for KAN-37, independent of module placement generation. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossSchemaFeasibilityTest {
    private var fixture: CrossSchemaTestDatabase? = null
    private val database get() = checkNotNull(fixture)
    private val allSchemas = "${CrossSchemaTestDatabase.PEOPLE},${CrossSchemaTestDatabase.ORDERS}"

    @BeforeAll
    fun start() {
        fixture = CrossSchemaTestDatabase()
        CrossSchemaHttpProbe.awaitReady(System.getenv("PG_CROSS_SCHEMA_HTTP_URL"))
    }

    @AfterAll
    fun stop() {
        fixture?.close()
    }

    @Test
    fun `one JDBC document traverses cross-schema FK and association rows`() = runBlocking { assertTraversal(jdbc()) }

    @Test
    fun `schema order does not shadow identically named physical tables`() =
        runBlocking {
            assertTraversal(jdbc(schemas = "${CrossSchemaTestDatabase.ORDERS},${CrossSchemaTestDatabase.PEOPLE}"))
        }

    @Test
    fun `HTTP traverses the same cross-schema graph`() =
        runBlocking {
            val endpoint = httpEndpoint()
            httpClient().use { client -> assertTraversal(HttpPgGraphqlExecutor(client, endpoint)) }
        }

    @Test
    fun `JDBC commits mixed-schema writes together`() = runBlocking { assertCommit(jdbc(), 10) }

    @Test
    fun `HTTP commits mixed-schema writes together`() =
        runBlocking {
            val endpoint = httpEndpoint()
            httpClient().use { client -> assertCommit(HttpPgGraphqlExecutor(client, endpoint), 11) }
        }

    @Test
    fun `JDBC rolls back both schemas on a late constraint failure`() = runBlocking { assertRollback(jdbc(), 20) }

    @Test
    fun `HTTP rolls back both schemas on a late constraint failure`() =
        runBlocking {
            val endpoint = httpEndpoint()
            httpClient().use { client -> assertRollback(HttpPgGraphqlExecutor(client, endpoint), 21) }
        }

    @Test
    fun `a schema omitted from search path is absent from GraphQL`() =
        runBlocking {
            val result = jdbc(schemas = CrossSchemaTestDatabase.PEOPLE).execute(query, emptyMap())
            assertTrue(result.errors.isNotEmpty(), result.toString())
        }

    @Test
    fun `search path does not grant schema privileges`() =
        runBlocking {
            val result = jdbc(role = CrossSchemaTestDatabase.LIMITED).execute(query, emptyMap())
            assertTrue(result.errors.isNotEmpty(), result.toString())
        }

    @Test
    fun `public default does not implicitly expose tenant schemas`() =
        runBlocking {
            val result = JdbcPgGraphqlExecutor(database.dataSource()).execute(query, emptyMap())
            assertTrue(result.errors.isNotEmpty(), result.toString())
        }

    @Test
    fun `transaction-local schema and role setup leave borrowed connection unchanged after rollback`() {
        database.withConnection { connection ->
            val before = CrossSchemaTestDatabase.state(connection)
            connection.autoCommit = false
            runBlocking {
                JdbcPgGraphqlExecutor(connection, setup()).execute(query, emptyMap())
            }
            connection.rollback()
            assertEquals(before, CrossSchemaTestDatabase.state(connection))
        }
    }

    @Test
    fun `one physical pooled connection restores identity after success failure and cancellation`() =
        runBlocking {
            pool(1).use { source ->
                val before = pooledState(source)
                assertTraversal(JdbcPgGraphqlExecutor(source, setup()))
                assertEquals(before, pooledState(source))
                assertRollback(JdbcPgGraphqlExecutor(source, setup()), 30)
                assertEquals(before, pooledState(source))
                val restricted = JdbcPgGraphqlExecutor(source, setup(role = CrossSchemaTestDatabase.LIMITED))
                assertTrue(restricted.execute(query, emptyMap()).errors.isNotEmpty())
                assertEquals(before, pooledState(source))

                lateinit var requestJob: Job
                val cancelled =
                    launch {
                        requestJob = requireNotNull(currentCoroutineContext()[Job])
                        val visibility = setup()
                        val executor =
                            JdbcPgGraphqlExecutor(
                                source,
                                JdbcRequestSetup { connection, headers ->
                                    visibility.configure(connection, headers)
                                    // Cancellation after setup still lets SQL run before the commit check.
                                    requestJob.cancel()
                                },
                            )
                        executor.execute(writes(31), emptyMap())
                    }
                cancelled.join()
                assertTrue(cancelled.isCancelled)
                assertEquals(0, database.rows(31))
                assertEquals(before, pooledState(source))
                assertTraversal(JdbcPgGraphqlExecutor(source, setup()))
                assertEquals(before, pooledState(source))
            }
        }

    @Test
    fun `concurrent pooled requests retain independent schema sets and database roles`() =
        runBlocking {
            pool(2).use { source ->
                val barrier = CyclicBarrier(2)
                val requests =
                    listOf(false, true).map { restricted ->
                        async(Dispatchers.IO) {
                            val visibility =
                                if (restricted) {
                                    setup(CrossSchemaTestDatabase.LIMITED, CrossSchemaTestDatabase.PEOPLE)
                                } else {
                                    setup()
                                }
                            val executor =
                                JdbcPgGraphqlExecutor(
                                    source,
                                    JdbcRequestSetup { connection, headers ->
                                        visibility.configure(connection, headers)
                                        barrier.await(15, TimeUnit.SECONDS)
                                    },
                                )
                            repeat(4) {
                                if (restricted) {
                                    assertTrue(executor.execute(query, emptyMap()).errors.isNotEmpty())
                                } else {
                                    assertTraversal(executor)
                                }
                            }
                        }
                    }
                requests.awaitAll()
                // Both physical connections must be clean once returned to the real pool.
                source.connection.use { first ->
                    source.connection.use { second ->
                        assertEquals(CrossSchemaTestDatabase.state(first), CrossSchemaTestDatabase.state(second))
                        assertTrue("kan37_" !in CrossSchemaTestDatabase.state(first))
                    }
                }
            }
        }

    private fun pool(size: Int) =
        HikariDataSource(
            HikariConfig().apply {
                dataSource = database.dataSource()
                maximumPoolSize = size
                minimumIdle = size
                connectionTimeout = 20_000
            },
        )

    private fun pooledState(source: HikariDataSource): Pair<Int, String> =
        source.connection.use { connection ->
            val backend = CrossSchemaTestDatabase.backend(connection)
            backend to CrossSchemaTestDatabase.state(connection)
        }

    private fun jdbc(
        role: String = CrossSchemaTestDatabase.READER,
        schemas: String = allSchemas,
    ) = JdbcPgGraphqlExecutor(database.dataSource(), setup(role, schemas))

    private fun setup(
        role: String = CrossSchemaTestDatabase.READER,
        schemas: String = allSchemas,
    ) = JdbcSchemaVisibility(
        schemas.split(","),
        JdbcRequestSetup { connection, headers ->
            require(headers.isEmpty())
            CrossSchemaTestDatabase.role(connection, role)
        },
    )

    private fun httpClient() = HttpClient(CIO) { expectSuccess = true }

    private suspend fun assertTraversal(executor: PgGraphqlExecutor) {
        val result = requireNotNull(executor.execute(query, emptyMap()))
        assertEquals(
            emptyList(),
            result.errors,
            result.toString(),
        )
        assertEquals(
            Json
                .parseToJsonElement(
                    """
                    {
                      "kan37PurchaseCollection": {"edges": [{"node": {
                        "label": "First purchase", "buyer": {"name": "Buyer"},
                        "reviews": {"edges": [{"node": {"reviewer": {"name": "Reviewer"}}}]}
                      }}]},
                      "kan37SellerCollection": {"edges": [{"node": {"name": "Seller"}}]}
                    }
                    """.trimIndent(),
                ).jsonObject,
            result.data,
        )
    }

    private suspend fun assertCommit(
        executor: PgGraphqlExecutor,
        id: Int,
    ) {
        val result = executor.execute(writes(id), emptyMap())
        assertEquals(emptyList(), result.errors, result.toString())
        assertEquals(2, database.rows(id))
    }

    private suspend fun assertRollback(
        executor: PgGraphqlExecutor,
        id: Int,
    ) {
        val result = executor.execute(writes(id, fail = true), emptyMap())
        assertTrue(result.errors.isNotEmpty(), result.toString())
        assertEquals(0, database.rows(id))
    }

    private fun httpEndpoint(): String {
        val endpoint = System.getenv("PG_CROSS_SCHEMA_HTTP_URL")
        assumeTrue(!endpoint.isNullOrBlank(), "Set PG_CROSS_SCHEMA_HTTP_URL to enable the PostgREST feasibility gate")
        return requireNotNull(endpoint)
    }

    private fun writes(
        id: Int,
        fail: Boolean = false,
    ): PgGraphqlRequest =
        PgGraphqlRequest(
            """
            mutation {
              a: insertIntoKan37AccountCollection(objects: [{id: $id, name: "New buyer"}]) { affectedCount }
              b: insertIntoKan37PurchaseCollection(objects: [{id: $id, buyerId: $id, label: "New purchase"}]) { affectedCount }
              ${if (fail) "c: insertIntoKan37AccountCollection(objects: [{id: $id, name: \"Duplicate\"}]) { affectedCount }" else ""}
            }
            """.trimIndent(),
        )

    private val query =
        PgGraphqlRequest(
            """
            {
              kan37PurchaseCollection(filter: {id: {eq: 1}}) {
                edges { node {
                  label
                  buyer { name }
                  reviews { edges { node { reviewer { name } } } }
                } }
              }
              kan37SellerCollection { edges { node { name } } }
            }
            """.trimIndent(),
        )
}
