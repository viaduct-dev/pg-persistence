package dev.viaduct.persistence.runtime.db

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import dev.viaduct.persistence.runtime.graphql.PgGraphqlExecutor
import dev.viaduct.persistence.runtime.graphql.PgGraphqlRequest
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.deferred.RequestParentJobContextElement
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DbTransactionsTest {
    @Test
    fun `blocking callback does not inherit Viaduct request parent job`() =
        runBlocking {
            val fixture = Fixture()
            val configured =
                object : BlockingDbTransactions() {
                    override fun <T> executeBlocking(
                        headers: Map<String, String>,
                        block: DbTransactionScope.() -> T,
                    ): DbTransactionCommit<T> = executeImmediateTransaction(fixture::execute, block)
                }
            val requestJob = requireNotNull(currentCoroutineContext()[Job])

            kotlinx.coroutines.withContext(RequestParentJobContextElement(requestJob)) {
                configured.execute(emptyMap()) {
                    assertNull(currentCoroutineContext()[RequestParentJobContextElement])
                    insert(entity, value)
                }
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cancelled caller cannot start configured transaction`(cancelInHeaders: Boolean) =
        runBlocking {
            val calls = mutableListOf<String>()
            val configured =
                object : BlockingDbTransactions() {
                    override fun <T> executeBlocking(
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
    fun `immediate operations remain serialized across coroutine threads`() {
        val fixture = Fixture()
        val committed =
            executeImmediateTransaction(fixture::execute) {
                lateinit var first: DbTransactionOperation
                val worker = Thread { first = insert(entity, value) }
                worker.start()
                worker.join()
                insert(entity, value)
                first
            }

        assertThat(fixture.requests.size).isEqualTo(2)
        assertThat(committed.result[committed.value]).isNotNull()
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
                object : BlockingDbTransactions() {
                    override fun <T> executeBlocking(
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

    @Test
    fun `ordinary client mutations join the configured immediate transaction`() =
        runBlocking {
            val fixture = Fixture()
            val client = immediateClient(fixture)

            val committed =
                client.transaction(mockk()) {
                    yield()
                    client.insertRaw(
                        mockk(),
                        PgGraphqlObject.of("name" to "Nested"),
                        "Member",
                    )
                }

            assertThat(
                committed.value
                    .getValue("records")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("uuidId"),
            ).isEqualTo(Json.parseToJsonElement("\"id\""))
            assertThat(fixture.requests.size).isEqualTo(1)
        }

    @Test
    fun `same client cannot start a nested configured transaction`() =
        runBlocking {
            val fixture = Fixture()
            val client = immediateClient(fixture)

            val failure =
                assertFailsWith<IllegalStateException> {
                    client.transaction(mockk()) {
                        client.transaction(mockk()) { insert(entity, value) }
                    }
                }

            assertThat(failure.message).isEqualTo("Nested transactions are not supported")
            assertThat(fixture.requests).isEmpty()
        }

    @Test
    fun `ordinary reads use the configured immediate transaction`() =
        runBlocking {
            val fixture = Fixture()
            val client = immediateClient(fixture)

            val committed =
                client.transaction(mockk()) {
                    val ids = client.fetchUuidIds(mockk(), "groupCollection")
                    assertThat(ids).isEqualTo(listOf("id"))
                    insert(entity, value)
                }

            assertThat(committed.result[committed.value]).isNotNull()
            assertThat(fixture.requests.size).isEqualTo(2)
        }

    @Test
    fun `another client does not join the active transaction`() =
        runBlocking {
            val fixture = Fixture()
            val owner = immediateClient(fixture)
            var ordinaryRequests = 0
            val other =
                DbClient(
                    PgGraphqlExecutor { _, _ ->
                        ordinaryRequests++
                        fixture.readResult()
                    },
                )

            owner.transaction(mockk()) {
                assertThat(other.fetchUuidIds(mockk(), "groupCollection")).isEqualTo(listOf("id"))
                insert(entity, value)
            }

            assertThat(ordinaryRequests).isEqualTo(1)
            assertThat(fixture.requests.size).isEqualTo(1)
        }

    @Test
    fun `active transaction is removed after commit`() =
        runBlocking {
            val fixture = Fixture()
            val client = immediateClient(fixture)
            client.transaction(mockk()) { insert(entity, value) }

            assertFailsWith<IllegalStateException> {
                client.fetchUuidIds(mockk(), "groupCollection")
            }
        }

    private fun immediateClient(fixture: Fixture): DbClient =
        DbClient(
            PgGraphqlExecutor { _, _ -> error("Must not use ordinary request execution") },
            transactions =
                object : BlockingDbTransactions() {
                    override fun <T> executeBlocking(
                        headers: Map<String, String>,
                        block: DbTransactionScope.() -> T,
                    ): DbTransactionCommit<T> = executeImmediateTransaction(fixture::execute, block)
                },
        )

    private class Fixture {
        val requests = mutableListOf<PgGraphqlRequest>()

        fun execute(request: PgGraphqlRequest): DbResult<kotlinx.serialization.json.JsonObject> {
            if (!request.isMutation) {
                requests += request
                return readResult()
            }
            val alias = "operation${requests.count { it.isMutation }}"
            requests += request
            val response = """{"$alias":{"affectedCount":1,"records":[{"uuidId":"id"}]}}"""
            return DbResult(Json.parseToJsonElement(response).jsonObject)
        }

        fun readResult(): DbResult<kotlinx.serialization.json.JsonObject> =
            DbResult(
                Json
                    .parseToJsonElement(
                        """{"groupCollection":{"edges":[{"node":{"uuidId":"id"}}]}}""",
                    ).jsonObject,
            )
    }

    companion object {
        private val entity = PgGraphqlEntity("Member")
        private val value = PgGraphqlObject.of("name" to "Guest")
    }
}
