package dev.viaduct.persistence.jdbc

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlException
import dev.viaduct.persistence.runtime.graphql.PgGraphqlRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.assertFailsWith

class JdbcExecutorTest {
    @Test
    fun `blocking execution retains transaction ownership and calling thread`() {
        val fixture = Fixture("""{"data":{"value":"ok"}}""")
        val callingThread = Thread.currentThread()
        val executor =
            JdbcPgGraphqlExecutor(
                fixture.connection,
                JdbcRequestSetup { _, _ ->
                    assertThat(Thread.currentThread()).isSameInstanceAs(callingThread)
                },
            )

        executor.executeBlocking(mutation, emptyMap())

        verify(exactly = 0) {
            fixture.connection.commit()
            fixture.connection.rollback()
            fixture.connection.close()
        }
    }

    @Test
    fun `blocking mutation errors still require caller rollback`() {
        val fixture = Fixture(PARTIAL)

        assertFailsWith<UpstreamGraphqlException> {
            JdbcPgGraphqlExecutor(fixture.connection).executeBlocking(mutation, emptyMap())
        }
        verify(exactly = 0) { fixture.connection.commit() }
    }

    @Test
    fun `success commits and closes only the borrowed resources`() =
        runBlocking {
            val fixture = Fixture("""{"data":{"value":"ok"}}""")
            fixture.executor().execute(query, emptyMap())

            verify(exactly = 1) {
                fixture.connection.commit()
                fixture.connection.close()
                fixture.rows.close()
            }
            verify(exactly = 0) { fixture.connection.rollback() }
        }

    @Test
    fun `partial query errors keep successful fields and their error paths`() =
        runBlocking {
            val fixture = Fixture(PARTIAL)
            val result = fixture.executor().execute(query, emptyMap())

            assertThat(
                result.data
                    ?.get("group")
                    ?.jsonObject
                    ?.get("description")
                    ?.jsonPrimitive
                    ?.content,
            ).isEqualTo("Guest community")
            assertThat(
                result.errors
                    .single()
                    .path
                    .map { it.jsonPrimitive.content },
            ).isEqualTo(listOf("group", "name"))
            verify(exactly = 1) { fixture.connection.rollback() }
            verify(exactly = 0) { fixture.connection.commit() }
        }

    @Test
    fun `mutation errors do not return rolled back data`() =
        runBlocking {
            val fixture = Fixture(PARTIAL)
            val result = fixture.executor().execute(mutation, emptyMap())

            assertThat(result.data).isNull()
            verify(exactly = 1) { fixture.connection.rollback() }
        }

    @Test
    fun `malformed envelope cannot commit writes`() =
        runBlocking {
            val fixture = Fixture("""{"unexpected":"response"}""")
            assertFailsWith<IllegalArgumentException> { fixture.executor().execute(mutation, emptyMap()) }

            verify(exactly = 1) {
                fixture.connection.rollback()
                fixture.connection.close()
            }
            verify(exactly = 0) { fixture.connection.commit() }
        }

    @Test
    fun `caller owned mutation failure leaves rollback to the caller`() =
        runBlocking {
            val fixture = Fixture(PARTIAL)
            assertFailsWith<UpstreamGraphqlException> {
                JdbcPgGraphqlExecutor(fixture.connection).execute(mutation, emptyMap())
            }

            verify(exactly = 0) {
                fixture.connection.commit()
                fixture.connection.rollback()
                fixture.connection.close()
            }
            verify(exactly = 1) { fixture.rows.close() }
        }

    @Test
    fun `caller owned success does not commit or close the connection`() =
        runBlocking {
            val fixture = Fixture("""{"data":{"value":"ok"}}""")
            JdbcPgGraphqlExecutor(fixture.connection).execute(mutation, emptyMap())

            verify(exactly = 0) {
                fixture.connection.commit()
                fixture.connection.rollback()
                fixture.connection.close()
            }
        }

    @Test
    fun `commit connection loss is propagated without retrying`() =
        runBlocking {
            val fixture = Fixture("""{"data":{"value":"ok"}}""")
            val lost = SQLException("Connection lost during commit", "08006")
            every { fixture.connection.commit() } throws lost

            assertThat(assertFailsWith<SQLException> { fixture.executor().execute(mutation, emptyMap()) })
                .isSameInstanceAs(lost)
            assertThat(fixture.connectionsOpened.get()).isEqualTo(1)
            verify(exactly = 1) { fixture.connection.commit() }
        }

    @Test
    fun `rollback failure is propagated rather than reported as a clean rejection`() =
        runBlocking {
            val fixture = Fixture(PARTIAL)
            every { fixture.connection.rollback() } throws SQLException("Rollback lost connection")

            assertFailsWith<SQLException> { fixture.executor().execute(mutation, emptyMap()) }
            verify(exactly = 0) { fixture.connection.commit() }
        }

    @Test
    fun `cancellation after JDBC returns prevents owned commit`() {
        val fixture = Fixture("""{"data":{"value":"ok"}}""")
        val job = Job()
        every { fixture.statement.executeQuery() } answers {
            job.cancel()
            fixture.rows
        }

        assertFailsWith<CancellationException> {
            runBlocking(job) { fixture.executor().execute(mutation, emptyMap()) }
        }
        verify(exactly = 1) {
            fixture.connection.rollback()
            fixture.connection.close()
        }
        verify(exactly = 0) { fixture.connection.commit() }
    }

    @Test
    fun `ambiguous operation is rejected before opening a connection`() =
        runBlocking {
            val fixture = Fixture("""{"data":{}}""")
            assertFailsWith<IllegalArgumentException> {
                fixture.executor().execute(PgGraphqlRequest("query One { a } mutation Two { b }"), emptyMap())
            }

            verify(exactly = 0) { fixture.source.connection }
        }

    private class Fixture(
        response: String,
    ) {
        val connection by lazy { mockk<Connection>(relaxed = true) }
        val source by lazy { mockk<DataSource>() }
        val statement by lazy { mockk<PreparedStatement>(relaxed = true) }
        val rows by lazy { mockk<ResultSet>(relaxed = true) }
        val connectionsOpened = AtomicInteger()

        init {
            every { source.connection } answers {
                connectionsOpened.incrementAndGet()
                connection
            }
            every { connection.prepareStatement(any()) } returns statement
            every { statement.executeQuery() } returns rows
            every { rows.next() } returns true
            every { rows.getString(1) } returns response
        }

        fun executor() = JdbcPgGraphqlExecutor(source)
    }

    companion object {
        private val query = PgGraphqlRequest("{ group { name description } }")
        private val mutation = PgGraphqlRequest("mutation { updateGroup { name } }")
        private const val PARTIAL =
            """{"data":{"group":{"name":null,"description":"Guest community"}},""" +
                """"errors":[{"message":"Could not read group name","path":["group","name"]}]}"""
    }
}
