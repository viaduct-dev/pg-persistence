package dev.viaduct.persistence.runtime.db

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import viaduct.api.context.ExecutionContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

class RetryableTransactionTest {
    @ParameterizedTest
    @ValueSource(
        strings = ["not json", "{\"data\":{}}", "{\"data\":{\"pgPersistenceExecuteTransaction\":\"{broken\"}}"],
    )
    fun `malformed or missing responses exhaust the retry budget as unknown`(body: String) =
        runBlocking {
            val requests = AtomicInteger()
            http {
                requests.incrementAndGet()
                body
            }.use { http ->
                val transaction = client(http).beginTransaction(ctx, "malformed")
                transaction.insert(ENTITY, values())
                val error = assertFailsWith<DbTransactionException> { transaction.commit() }
                assertThat(error.outcome).isEqualTo(DbTransactionOutcome.UNKNOWN)
                assertThat(requests.get()).isEqualTo(3)
            }
        }

    @Test
    fun `an I O failure retries the frozen request and preserves its cause on exhaustion`() =
        runBlocking {
            val failure = IOException("connection lost")
            val requests = AtomicInteger()
            http {
                requests.incrementAndGet()
                throw failure
            }.use { http ->
                val transaction = client(http).beginTransaction(ctx, "lost")
                transaction.insert(ENTITY, values())
                val prepared = transaction.prepare()
                val error = assertFailsWith<DbTransactionException> { transaction.commit() }
                assertThat(generateSequence(error.cause) { it.cause }.last()).isSameInstanceAs(failure)
                assertThat(error.prepared.encode()).isEqualTo(prepared.encode())
                assertThat(requests.get()).isEqualTo(3)
            }
        }

    @ParameterizedTest
    @MethodSource("nonRetryableFailures")
    fun `unrecognized failures propagate without retry`(failure: Throwable) =
        runBlocking {
            val requests = AtomicInteger()
            http {
                requests.incrementAndGet()
                throw failure
            }.use { http ->
                val transaction = client(http).beginTransaction(ctx, "failed")
                transaction.insert(ENTITY, values())
                val thrown = assertFailsWith<Throwable> { transaction.commit() }
                assertThat(thrown.javaClass).isEqualTo(failure.javaClass)
                assertThat(generateSequence(thrown) { it.cause }.last()).isSameInstanceAs(failure)
                assertThat(requests.get()).isEqualTo(1)
            }
        }

    @Test
    fun `an unknown protocol status is not mistaken for a transport failure`() =
        runBlocking {
            val requests = AtomicInteger()
            http {
                requests.incrementAndGet()
                envelope("""{"status":"unexpected"}""")
            }.use { http ->
                val transaction = client(http).beginTransaction(ctx, "unknown-status")
                transaction.insert(ENTITY, values())
                val error = assertFailsWith<DbTransactionException> { transaction.commit() }
                assertThat(error.outcome).isEqualTo(DbTransactionOutcome.UNKNOWN)
                assertThat(requests.get()).isEqualTo(1)
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["40001", "40P01", "55P03"])
    fun `structured transient database errors are retried`(code: String) =
        runBlocking {
            val requests = AtomicInteger()
            http {
                if (requests.incrementAndGet() == 1) {
                    envelope("""{"status":"rolledBack","code":"$code","errors":[{"message":"retry"}]}""")
                } else {
                    envelope(
                        """{"status":"committed","response":{"data":{
                            "operation0":{"affectedCount":1,"records":[]}
                        }}}""",
                    )
                }
            }.use { http ->
                val transaction = client(http).beginTransaction(ctx, "transient")
                transaction.insert(ENTITY, values())
                transaction.commit()
                assertThat(requests.get()).isEqualTo(2)
            }
        }

    @Test
    fun `deadline exhaustion reports unknown with the frozen request`() =
        runBlocking {
            val requests = AtomicInteger()
            HttpClient(
                MockEngine {
                    requests.incrementAndGet()
                    awaitCancellation()
                },
            ).use { http ->
                val transaction = client(http, timeoutMillis = 1_000).beginTransaction(ctx, "deadline")
                transaction.insert(ENTITY, values())
                val error = assertFailsWith<DbTransactionException> { transaction.commit() }
                assertThat(error.outcome).isEqualTo(DbTransactionOutcome.UNKNOWN)
                assertThat(error.prepared.operationId).isEqualTo("deadline")
                assertThat(requests.get()).isEqualTo(1)
            }
        }

    @Test
    fun `missing server support does not retry or fall back to an ordinary mutation`() =
        runBlocking {
            val requests = AtomicInteger()
            http {
                requests.incrementAndGet()
                """{"errors":[{"message":"Unknown field pgPersistenceExecuteTransaction"}]}"""
            }.use { http ->
                val transaction = client(http).beginTransaction(ctx, "missing")
                transaction.insert(ENTITY, values())
                val error = assertFailsWith<DbTransactionException> { transaction.commit() }
                assertThat(error.outcome).isEqualTo(DbTransactionOutcome.REJECTED)
                assertThat(requests.get()).isEqualTo(1)
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "null", "[]", "\"invalid\""])
    fun `a committed result decoding failure is reported as committed`(response: String) =
        runBlocking {
            val requests = AtomicInteger()
            http {
                requests.incrementAndGet()
                envelope("""{"status":"committed","response":$response}""")
            }.use { http ->
                val transaction = client(http).beginTransaction(ctx, "bad-result")
                transaction.insert(ENTITY, values())
                val error = assertFailsWith<DbTransactionException> { transaction.commit() }
                assertThat(error.outcome).isEqualTo(DbTransactionOutcome.COMMITTED)
                assertThat(requests.get()).isEqualTo(1)
            }
        }

    @Test
    fun `cancellation is propagated and does not start another attempt`() =
        runBlocking {
            val requests = AtomicInteger()
            http {
                requests.incrementAndGet()
                throw CancellationException("cancelled")
            }.use { http ->
                val transaction = client(http).beginTransaction(ctx, "cancel")
                transaction.insert(ENTITY, values())
                val prepared = transaction.prepare()
                assertFailsWith<CancellationException> { transaction.commit() }
                assertThat(prepared.operationId).isEqualTo("cancel")
                assertThat(requests.get()).isEqualTo(1)
            }
        }

    @Test
    fun `rolled back GraphQL errors preserve paths without exposing partial data`() =
        runBlocking {
            http {
                envelope(
                    """{"status":"rolledBack","errors":[
                        {"message":"missing","path":["operation1"],"extensions":{"code":"MISSING"}}
                    ]}""",
                )
            }.use { http ->
                val transaction = client(http).beginTransaction(ctx, "rollback")
                transaction.insert(ENTITY, values())
                val result = transaction.commitResult()
                assertThat(result.data).isNull()
                assertThat(
                    result.errors
                        .single()
                        .path
                        .toString(),
                ).isEqualTo("[\"operation1\"]")
            }
        }

    @Test
    fun `changing scope after preparing fails before sending`() =
        runBlocking {
            var scope = "first"
            val requests = AtomicInteger()
            http {
                requests.incrementAndGet()
                error("must not send")
            }.use { http ->
                val transaction =
                    DbClient(
                        http,
                        "http://fixture/graphql",
                        retryableTransactions = DbRetryableTransactions(DbTransactionIdentity { scope }),
                    ).beginTransaction(ctx, "scope")
                transaction.insert(ENTITY, values())
                transaction.prepare()
                scope = "second"
                assertFailsWith<IllegalArgumentException> { transaction.commit() }
                assertThat(requests.get()).isEqualTo(0)
            }
        }

    @Test
    fun `preparation freezes commands and abort still discards them`() =
        runBlocking {
            val requests = AtomicInteger()
            http {
                requests.incrementAndGet()
                error("must not send")
            }.use { http ->
                val transaction = client(http).beginTransaction(ctx, "abort")
                transaction.insert(ENTITY, values())
                transaction.prepare()
                assertFailsWith<IllegalStateException> { transaction.insert(ENTITY, values()) }
                transaction.abort()
                assertFailsWith<IllegalStateException> { transaction.commit() }
                assertThat(requests.get()).isEqualTo(0)
            }
        }

    private fun http(response: () -> String) =
        HttpClient(
            MockEngine { respond(response(), headers = headersOf(HttpHeaders.ContentType, "application/json")) },
        )

    private fun client(
        http: HttpClient,
        timeoutMillis: Long = 30_000,
    ) = DbClient(
        http,
        "http://fixture/graphql",
        retryableTransactions =
            DbRetryableTransactions(
                DbTransactionIdentity { "trusted" },
                initialDelayMillis = 0,
                timeoutMillis = timeoutMillis,
            ),
    )

    private fun envelope(body: String): String =
        buildJsonObject {
            put("data", buildJsonObject { put("pgPersistenceExecuteTransaction", body) })
        }.toString()

    private fun values() = PgGraphqlObject.of("uuidId" to "11111111-1111-4111-8111-111111111111", "name" to "Group")

    companion object {
        @JvmStatic
        fun nonRetryableFailures(): List<Throwable> =
            listOf(
                IllegalArgumentException("invalid argument"),
                AssertionError("fatal"),
            )

        private val ENTITY = PgGraphqlEntity("Group")
        private val ctx = mockk<ExecutionContext>()
    }
}
