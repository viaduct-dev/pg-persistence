package dev.viaduct.persistence.runtime.db

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import dev.viaduct.persistence.runtime.graphql.PgGraphqlExecutor
import dev.viaduct.persistence.runtime.graphql.PgGraphqlRequest
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DbTransactionsTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cancelled caller cannot start configured transaction`(cancelInHeaders: Boolean) =
        runBlocking {
            val calls = mutableListOf<String>()
            val configured =
                object : DbTransactions {
                    override fun <T> execute(
                        headers: Map<String, String>,
                        block: DbTransactionScope.() -> T,
                    ): DbTransactionCommit<T> {
                        calls += "transaction"
                        return executeImmediateTransaction(Fixture()::execute, block)
                    }
                }
            val client =
                DbClient(
                    PgGraphqlExecutor { _, _ -> error("Unexpected ordinary request") },
                    requestHeaders =
                        DbRequestHeaders {
                            if (cancelInHeaders) currentCoroutineContext().cancel()
                            emptyMap()
                        },
                    transactions = configured,
                )
            launch {
                if (!cancelInHeaders) currentCoroutineContext().cancel()
                assertFailsWith<CancellationException> { client.transaction(mockk()) { insert(entity, value) } }
            }.join()
            assertThat(calls).isEmpty()
        }

    @Test
    fun `operations execute before the block advances`() {
        val fixture = Fixture()
        val committed =
            executeImmediateTransaction(fixture::execute) {
                insert(entity, value)
                assertThat(fixture.requests.size).isEqualTo(1)
                insert(entity, value)
            }

        assertThat(fixture.requests.size).isEqualTo(2)
        assertThat(committed.result[committed.value]).isNotNull()
    }

    @Test
    fun `caught mutation failure still fails the transaction`() {
        val fixture = Fixture()
        val failure = IllegalArgumentException("Failed mutation")
        assertFailsWith<IllegalArgumentException> {
            executeImmediateTransaction({ request ->
                if (fixture.requests.isNotEmpty()) throw failure
                fixture.execute(request)
            }) {
                insert(entity, value)
                runCatching { insert(entity, value) }
            }
        }
    }

    @Test
    fun `escaped scope cannot send more requests`() {
        val fixture = Fixture()
        lateinit var scope: DbTransactionScope
        executeImmediateTransaction(fixture::execute) {
            scope = this
            insert(entity, value)
        }
        assertFailsWith<IllegalStateException> { scope.insert(entity, value) }
        assertThat(fixture.requests.size).isEqualTo(1)
    }

    @Test
    fun `another thread cannot execute mutations`() {
        val fixture = Fixture()
        assertFailsWith<IllegalStateException> {
            executeImmediateTransaction(fixture::execute) {
                val worker = Thread { runCatching { insert(entity, value) } }
                worker.start()
                worker.join()
            }
        }
        assertThat(fixture.requests).isEmpty()
    }

    @Test
    fun `empty transaction is rejected`() {
        assertFailsWith<IllegalArgumentException> { executeImmediateTransaction(Fixture()::execute) { Unit } }
    }

    @Test
    fun `invalid mutation payload prevents successful return`() {
        assertFailsWith<IllegalArgumentException> {
            executeImmediateTransaction({ DbResult(Json.parseToJsonElement("""{"operation0":{}}""").jsonObject) }) {
                insert(entity, value)
            }
        }
    }

    @Test
    fun `client passes headers and uses configured implementation`() =
        runBlocking {
            val fixture = Fixture()
            val transactions =
                object : DbTransactions {
                    override fun <T> execute(
                        headers: Map<String, String>,
                        block: DbTransactionScope.() -> T,
                    ): DbTransactionCommit<T> {
                        assertThat(headers).isEqualTo(mapOf("trace" to "request-1"))
                        return executeImmediateTransaction(fixture::execute, block)
                    }
                }
            val client =
                DbClient(
                    PgGraphqlExecutor { _, _ -> error("Must not use ordinary request execution") },
                    requestHeaders = DbRequestHeaders { mapOf("trace" to "request-1") },
                    transactions = transactions,
                )
            val committed = client.transaction(mockk()) { insert(entity, value) }
            assertThat(committed.result[committed.value]).isNotNull()
            assertFailsWith<IllegalStateException> { client.beginTransaction(mockk()) }
            assertFailsWith<IllegalStateException> { client.transaction(mockk(), "http-id") { insert(entity, value) } }
        }

    private class Fixture {
        val requests = mutableListOf<PgGraphqlRequest>()

        fun execute(request: PgGraphqlRequest): DbResult<kotlinx.serialization.json.JsonObject> {
            val alias = "operation${requests.size}"
            requests += request
            val response = """{"$alias":{"affectedCount":1,"records":[{"uuidId":"id"}]}}"""
            return DbResult(Json.parseToJsonElement(response).jsonObject)
        }
    }

    companion object {
        private val entity = PgGraphqlEntity("Member")
        private val value = PgGraphqlObject.of("name" to "Guest")
    }
}
