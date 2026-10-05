package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.graphql.PgGraphqlExecutor
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import viaduct.api.context.ExecutionContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class TransactionNestingTest {
    enum class Mode { BUFFERED, IDENTIFIED, IMMEDIATE }

    private val context by lazy { mockk<ExecutionContext>() }
    private val entity = PgGraphqlEntity("Member")
    private val value = PgGraphqlObject.of("name" to "Member")

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `every outer mode rejects nested execution before acquiring resources`(outerMode: Mode) =
        runBlocking {
            val requests = AtomicInteger()
            val adapter = adapter(requests)
            adapter.use {
                val outer = client(outerMode, adapter, requests)
                val failures = mutableListOf<String?>()
                execute(outer, outerMode) {
                    for (innerMode in Mode.entries) {
                        val inner = client(innerMode, adapter, requests)
                        failures +=
                            rejection {
                                withContext(Dispatchers.Default) {
                                    execute(inner, innerMode) { error("Nested body ran") }
                                }
                            }
                    }
                    failures += rejection { execute(outer, outerMode) { error("Same-client nested body ran") } }
                    val buffered = client(Mode.BUFFERED, adapter, requests).beginTransaction(context)
                    buffered.insert(entity, value)
                    failures += rejection { buffered.commit() }
                    failures += rejection { buffered.commitResult() }
                    val retryable = client(Mode.IDENTIFIED, adapter, requests)
                    val prepared =
                        DbPreparedTransaction.create(
                            "operation",
                            "scope",
                            buildJsonObject {
                                put("protocolVersion", 1)
                                put("operationName", "DbTransaction")
                                put(
                                    "document",
                                    """
                                    mutation DbTransaction {
                                        operation0: insertIntoMemberCollection { affectedCount }
                                    }
                                    """.trimIndent(),
                                )
                                put("variables", buildJsonObject {})
                            },
                            1,
                        )
                    failures += rejection { retryable.resumeTransaction(context, prepared) }
                    insert(entity, value)
                }
                execute(outer, outerMode) { insert(entity, value) }
                assertEquals<Any>(List(7) { "Nested transactions are not supported" } to 2, failures to requests.get())
            }
        }

    @Test
    fun `sequential transactions remain legal after rejection and failure`() =
        runBlocking {
            val requests = AtomicInteger()
            adapter(requests).use { adapter ->
                val client = client(Mode.BUFFERED, adapter, requests)
                rejection { client.transaction(context) { throw IllegalStateException("Rejected outer") } }
                val result = client.transaction(context) { insert(entity, value) }
                assertEquals(true to 1, (result.result[result.value] != null) to requests.get())
            }
        }

    private suspend fun rejection(block: suspend () -> Any?): String? =
        try {
            block()
            "accepted"
        } catch (failure: IllegalStateException) {
            failure.message
        }

    private suspend fun <T> execute(
        client: DbClient,
        mode: Mode,
        block: suspend DbTransactionScope.() -> T,
    ): DbTransactionCommit<T> =
        if (mode == Mode.IDENTIFIED) {
            client.transaction(context, "operation", block)
        } else {
            client.transaction(context, block)
        }

    private fun client(
        mode: Mode,
        adapter: BlockingDbTransactions,
        requests: AtomicInteger,
    ): DbClient =
        DbClient(
            PgGraphqlExecutor { request, _ ->
                requests.incrementAndGet()
                val payload = Json.parseToJsonElement("""{"operation0":{"affectedCount":1,"records":[]}}""").jsonObject
                DbResult(
                    if ("pgPersistenceExecuteTransaction" in request.document) {
                        buildJsonObject {
                            put(
                                "pgPersistenceExecuteTransaction",
                                buildJsonObject {
                                    put("status", "committed")
                                    put("response", buildJsonObject { put("data", payload) })
                                }.toString(),
                            )
                        }
                    } else {
                        payload
                    },
                )
            },
            requestHeaders = DbRequestHeaders { emptyMap() },
            transactions = adapter.takeIf { mode == Mode.IMMEDIATE },
            retryableTransactions =
                if (mode != Mode.IMMEDIATE) DbRetryableTransactions(DbTransactionIdentity { "scope" }) else null,
        )

    private fun adapter(requests: AtomicInteger) =
        object : BlockingDbTransactions() {
            override fun <T> executeBlocking(
                headers: Map<String, String>,
                block: DbTransactionScope.() -> T,
            ): DbTransactionCommit<T> =
                executeImmediateTransaction({
                    requests.incrementAndGet()
                    DbResult(Json.parseToJsonElement("""{"operation0":{"affectedCount":1,"records":[]}}""").jsonObject)
                }, block)
        }
}
