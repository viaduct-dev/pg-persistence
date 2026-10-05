package dev.viaduct.persistence.jdbc

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isNull
import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.PgGraphqlEntity
import dev.viaduct.persistence.runtime.db.PgGraphqlMutationClient
import dev.viaduct.persistence.runtime.db.PgGraphqlObject
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlException
import dev.viaduct.persistence.runtime.graphql.PgGraphqlRequest
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import viaduct.api.context.ExecutionContext
import java.util.UUID
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcTransportIntegrationTest {
    private val database by lazy { JdbcTestDatabase() }
    private val member = PgGraphqlEntity("JdbcMember")
    private val context by lazy { mockk<ExecutionContext>() }

    @BeforeAll
    fun start() {
        database
    }

    @AfterAll
    fun stop() {
        database.close()
    }

    @Test
    fun `insert through mutation client can be read through DbClient`() =
        runBlocking {
            val executor = JdbcPgGraphqlExecutor(database.dataSource())
            val id = UUID.randomUUID().toString()
            PgGraphqlMutationClient(executor).insert(member, value(id, "Guest community"))

            assertThat(DbClient(executor).fetchUuidIds(context, "jdbcMemberCollection")).contains(id)
        }

    @Test
    fun `update uses the existing mutation API`() =
        runBlocking {
            val client = PgGraphqlMutationClient(JdbcPgGraphqlExecutor(database.dataSource()))
            val id = UUID.randomUUID().toString()
            client.insert(member, value(id, "Original"))
            client.update(member, buildJsonObject { put("name", "Updated") }, filter(id), 1)

            assertThat(database.name(id)).isEqualTo("Updated")
        }

    @Test
    fun `delete uses the existing mutation API`() =
        runBlocking {
            val client = PgGraphqlMutationClient(JdbcPgGraphqlExecutor(database.dataSource()))
            val id = UUID.randomUUID().toString()
            client.insert(member, value(id, "Delete me"))
            client.delete(member, filter(id), 1)

            assertThat(database.name(id)).isNull()
        }

    @Test
    fun `buffered transaction commits both operations`() =
        runBlocking {
            val transaction = DbClient(JdbcPgGraphqlExecutor(database.dataSource())).beginTransaction(context)
            val first = UUID.randomUUID().toString()
            val second = UUID.randomUUID().toString()
            transaction.insert(member, value(first, "First"))
            transaction.insert(member, value(second, "Second"))
            transaction.commit()

            assertThat(listOf(database.name(first), database.name(second))).isEqualTo(listOf("First", "Second"))
        }

    @Test
    fun `late mutation failure rolls back and returns no committed data`() =
        runBlocking {
            val transaction = DbClient(JdbcPgGraphqlExecutor(database.dataSource())).beginTransaction(context)
            val id = UUID.randomUUID().toString()
            transaction.insert(member, value(id, "First"))
            transaction.insert(member, value(id, "Duplicate"))
            val result = transaction.commitResult()

            assertThat(result.errors).isNotEmpty()
            assertThat(result.data).isNull()
            assertThat(database.name(id)).isNull()
        }

    @Test
    fun `caller decides when writes become visible`() {
        val id = UUID.randomUUID().toString()
        database.withConnection { connection ->
            connection.autoCommit = false
            runBlocking {
                PgGraphqlMutationClient(JdbcPgGraphqlExecutor(connection)).insert(member, value(id, "Pending"))
            }
            assertThat(database.name(id)).isNull()
            connection.commit()
        }
        assertThat(database.name(id)).isEqualTo("Pending")
    }

    @Test
    fun `caller can roll back several separate GraphQL calls`() {
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        database.withConnection { connection ->
            connection.autoCommit = false
            val client = PgGraphqlMutationClient(JdbcPgGraphqlExecutor(connection))
            runBlocking {
                client.insert(member, value(first, "First"))
                client.insert(member, value(second, "Second"))
            }
            connection.rollback()
            assertThat(connection.isClosed).isFalse()
        }
        assertThat(listOf(database.name(first), database.name(second))).isEqualTo(listOf(null, null))
    }

    @Test
    fun `caller receives mutation errors and rolls back earlier writes`() {
        val id = UUID.randomUUID().toString()
        database.withConnection { connection ->
            connection.autoCommit = false
            val client = PgGraphqlMutationClient(JdbcPgGraphqlExecutor(connection))
            runBlocking {
                client.insert(member, value(id, "First"))
                assertFailsWith<UpstreamGraphqlException> { client.insert(member, value(id, "Duplicate")) }
            }
            connection.rollback()
        }
        assertThat(database.name(id)).isNull()
    }

    @Test
    fun `borrowed autocommit connection is rejected`() {
        database.withConnection { connection ->
            runBlocking {
                assertFailsWith<IllegalStateException> {
                    JdbcPgGraphqlExecutor(connection).execute(PgGraphqlRequest("{ __typename }"), emptyMap())
                }
            }
            assertThat(connection.isClosed).isFalse()
        }
    }

    @Test
    fun `variable contents are not interpreted as SQL or GraphQL`() =
        runBlocking {
            val id = UUID.randomUUID().toString()
            val name = "Guest\"); DROP TABLE member; -- ' \\"
            PgGraphqlMutationClient(JdbcPgGraphqlExecutor(database.dataSource())).insert(member, value(id, name))

            assertThat(database.name(id)).isEqualTo(name)
        }

    @Test
    fun `explicit operation name selects the requested query`() =
        runBlocking {
            val request =
                PgGraphqlRequest(
                    "query First { a: __typename } query Second { b: __typename }",
                    operationName = "Second",
                )
            val result = JdbcPgGraphqlExecutor(database.dataSource()).execute(request, emptyMap())

            assertThat(result.data?.keys).isEqualTo(setOf("b"))
            assertThat(result.errors).isEmpty()
        }

    @Test
    fun `headers require explicit JDBC configuration`() =
        runBlocking {
            assertFailsWith<IllegalArgumentException> {
                JdbcPgGraphqlExecutor(database.dataSource()).execute(
                    PgGraphqlRequest("{ __typename }"),
                    mapOf("x-trace" to "test"),
                )
            }
            Unit
        }

    @Test
    fun `trusted setup can handle request metadata on the same connection`() =
        runBlocking {
            val seen = mutableListOf<String>()
            val executor =
                JdbcPgGraphqlExecutor(
                    database.dataSource(),
                    JdbcRequestSetup { connection, headers ->
                        val label = headers.getValue("x-trace")
                        JdbcTestDatabase.setLabel(connection, label)
                        seen.add(label)
                    },
                )
            val result = executor.execute(PgGraphqlRequest("{ __typename }"), mapOf("x-trace" to "jdbc-test"))

            assertThat(seen).isEqualTo(listOf("jdbc-test"))
            assertThat(
                result.data
                    ?.get("__typename")
                    ?.jsonPrimitive
                    ?.content,
            ).isEqualTo("Query")
        }

    private fun value(
        id: String,
        name: String,
    ): PgGraphqlObject = PgGraphqlObject.of("uuidId" to id, "name" to name)

    private fun filter(id: String) = buildJsonObject { put("uuidId", buildJsonObject { put("eq", id) }) }
}
