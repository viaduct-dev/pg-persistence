package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.graphql.PgGraphqlExecutor
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Regression coverage for cancellation and paginated parent connections. */
class DbTransactionsAndConnectionsRegressionTest {
    @Test
    fun `cancelled owner admission never opens another transaction`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val queued = CompletableDeferred<Unit>()
            val calls =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val adapter =
                object : BlockingDbTransactions(1) {
                    override fun <T> executeBlocking(
                        headers: Map<String, String>,
                        block: DbTransactionScope.() -> T,
                    ): DbTransactionCommit<T> {
                        calls.incrementAndGet()
                        return executeImmediateTransaction({
                            DbResult(
                                Json
                                    .parseToJsonElement(
                                        """{"operation0":{"affectedCount":1,"records":[]}}""",
                                    ).jsonObject,
                            )
                        }, block)
                    }
                }
            adapter.use {
                val first =
                    launch {
                        adapter.execute(emptyMap()) {
                            entered.complete(Unit)
                            release.await()
                            insert(PgGraphqlEntity("Member"), PgGraphqlObject.of("name" to "First"))
                        }
                    }
                withTimeout(5000) {
                    entered.await()
                    val second =
                        launch {
                            queued.complete(Unit)
                            adapter.execute(emptyMap()) { error("Cancelled queued transaction entered") }
                        }
                    queued.await()
                    second.cancel()
                    second.join()
                    release.complete(Unit)
                    first.join()
                }
                assertEquals(1, calls.get())
            }
        }

    @Test
    fun `blocking failures preserve their identity and suppressed cleanup errors`() =
        runBlocking {
            val original =
                java.sql.SQLException("Original JDBC failure").apply {
                    addSuppressed(java.sql.SQLException("Rollback failure"))
                }
            val adapter =
                object : BlockingDbTransactions() {
                    override fun <T> executeBlocking(
                        headers: Map<String, String>,
                        block: DbTransactionScope.() -> T,
                    ): DbTransactionCommit<T> = throw original
                }
            adapter.use {
                val client =
                    DbClient(
                        PgGraphqlExecutor {
                            _,
                            _,
                            ->
                            error("Unexpected ordinary request")
                        },
                        transactions = adapter,
                    )
                val failure = runCatching { client.transaction(mockk()) { Unit } }.exceptionOrNull()
                assertEquals<Any>(
                    true to listOf("Rollback failure"),
                    (failure === original) to failure?.suppressed?.map { it.message },
                )
            }
        }

    @Test
    fun `callback inherits the active step context across dispatcher hops`() =
        runBlocking {
            val local = ThreadLocal<String>()
            local.set("workflow")
            val adapter =
                object : BlockingDbTransactions() {
                    override fun invocationContext() = local.asContextElement()

                    override fun <T> executeBlocking(
                        headers: Map<String, String>,
                        block: DbTransactionScope.() -> T,
                    ): DbTransactionCommit<T> {
                        local.set("step-1")
                        return executeImmediateTransaction({
                            DbResult(
                                Json
                                    .parseToJsonElement(
                                        """{"operation0":{"affectedCount":1,"records":[]}}""",
                                    ).jsonObject,
                            )
                        }, block)
                    }
                }
            try {
                adapter.use {
                    val result =
                        adapter.execute(emptyMap()) {
                            val before = local.get()
                            val after =
                                withContext(Dispatchers.Default) {
                                    yield()
                                    local.get()
                                }
                            insert(PgGraphqlEntity("Member"), PgGraphqlObject.of("name" to "Member"))
                            before to after
                        }
                    assertEquals(("step-1" to "step-1") to "workflow", result.value to local.get())
                }
            } finally {
                local.remove()
            }
        }

    @Test
    fun `cancelling the caller before a write prevents transaction commit`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val committed = AtomicBoolean()
            val configured =
                object : BlockingDbTransactions() {
                    override fun <T> executeBlocking(
                        headers: Map<String, String>,
                        block: DbTransactionScope.() -> T,
                    ): DbTransactionCommit<T> {
                        val response = """{"operation0":{"affectedCount":1,"records":[{"uuidId":"id"}]}}"""
                        val result =
                            executeImmediateTransaction({
                                DbResult(Json.parseToJsonElement(response).jsonObject)
                            }, block)
                        committed.set(true)
                        return result
                    }
                }
            val caller =
                launch(Dispatchers.Default) {
                    configured.execute(emptyMap()) {
                        entered.complete(Unit)
                        release.await()
                        insert(PgGraphqlEntity("Member"), PgGraphqlObject.of("name" to "After cancellation"))
                    }
                }
            withTimeout(5000) {
                entered.await()
                caller.cancel()
                release.complete(Unit)
                caller.join()
            }
            assertFalse(committed.get(), "Transaction committed after caller cancellation")
        }

    @Test
    fun `nested connections fetch every requested parent despite an upstream row limit`() =
        runBlocking {
            var calls = 0
            val client =
                DbClient(
                    PgGraphqlExecutor { request, _ ->
                        calls++
                        val ids = request.variables.getValue("parentIds") as kotlinx.serialization.json.JsonArray
                        val after = request.variables["parentAfter"]?.toString()?.trim('"')
                        val previous = ids.indexOfFirst { it.toString().trim('"') == after }
                        val parent = ids[previous + 1].toString()
                        val hasNext = parent != ids.last().toString()
                        DbResult(
                            Json
                                .parseToJsonElement(
                                    """{
              "memberCollection":{"edges":[{"cursor":$parent,"node":{"uuidId":$parent,
                "friendsCollection":{"edges":[],"pageInfo":{"hasNextPage":false,"hasPreviousPage":false,"startCursor":null,"endCursor":null}}
              }}],"pageInfo":{"hasNextPage":$hasNext,"endCursor":$parent}}
            }""",
                                ).jsonObject,
                        )
                    },
                )
            val pages =
                client.fetchNestedUuidConnections(
                    mockk(),
                    "memberCollection",
                    listOf("parent-1", "parent-2", "parent-3"),
                    "friendsCollection",
                    first = 2,
                )
            assertEquals(
                setOf("parent-1", "parent-2", "parent-3"),
                pages.keys,
                "Returned ${pages.keys} after $calls upstream request(s)",
            )
        }
}
