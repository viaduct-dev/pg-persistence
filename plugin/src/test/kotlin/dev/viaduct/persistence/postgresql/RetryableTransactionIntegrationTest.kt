package dev.viaduct.persistence.postgresql

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import dev.viaduct.persistence.jdbc.JdbcOperations
import dev.viaduct.persistence.runtime.db.DbPreparedTransaction
import dev.viaduct.persistence.runtime.db.DbTransactionException
import dev.viaduct.persistence.runtime.db.DbTransactionOutcome
import dev.viaduct.persistence.runtime.db.PgGraphqlDelete
import dev.viaduct.persistence.runtime.db.PgGraphqlEntity
import dev.viaduct.persistence.runtime.db.PgGraphqlFilter
import dev.viaduct.persistence.runtime.db.PgGraphqlObject
import dev.viaduct.persistence.runtime.db.PgGraphqlUpdate
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

class RetryableTransactionIntegrationTest {
    @Test
    fun `failure storing a result rolls back the business write`() =
        withFixture { f ->
            f.connection().use { database ->
                JdbcOperations.execute(
                    database,
                    """
                    ALTER TABLE persistence_private.transaction_records
                    ADD CONSTRAINT reject_result CHECK (false);
                    """.trimIndent(),
                )
            }
            assertFailsWith<UpstreamGraphqlException> {
                f.client().transaction(f.context, "storage-failed") { insert(ENTITY, values()) }
            }
            assertThat(listOf(f.countMembers(), f.countRecords())).isEqualTo(listOf(0, 0))
        }

    @Test
    fun `updates and deletes recover original affected rows even after those rows are gone`() =
        withFixture { f ->
            val id = UUID.randomUUID().toString()
            val client = f.client()
            client.transaction(f.context) { insert(ENTITY, PgGraphqlObject.of("uuidId" to id, "name" to "Before")) }
            val transaction = client.beginTransaction(f.context, "update-delete")
            transaction.update(
                ENTITY,
                PgGraphqlUpdate(PgGraphqlObject.of("name" to "After"), PgGraphqlFilter.eq("uuidId", id)),
            )
            val deleted = transaction.delete(ENTITY, PgGraphqlDelete(PgGraphqlFilter.eq("uuidId", id)))
            val prepared = transaction.prepare()
            val first = transaction.commit()
            val replay = client.resumeTransaction(f.context, prepared)
            assertThat(first[deleted]).isEqualTo(replay[deleted])
            assertThat(f.countMembers()).isEqualTo(0)
        }

    @Test
    fun `in flight operation is not reported as rolled back by lookup or exhausted retries`() =
        withFixture { f ->
            val client = f.client(attempts = 1)
            val transaction = client.beginTransaction(f.context, "locked")
            transaction.insert(ENTITY, values())
            val prepared = transaction.prepare()
            f.connection().use { blocking ->
                blocking.autoCommit = false
                JdbcOperations.execute(
                    blocking,
                    "select pg_advisory_xact_lock(hashtextextended(" +
                        "jsonb_build_array(current_user, ?::text, ?::text)::text, 0))",
                    prepared.scope,
                    prepared.operationId,
                )
                assertThat(client.lookupTransaction(f.context, prepared.operationId)).isNull()
                val error = assertFailsWith<DbTransactionException> { client.resumeTransaction(f.context, prepared) }
                assertThat(error.outcome).isEqualTo(DbTransactionOutcome.UNKNOWN)
                blocking.rollback()
            }
            client.resumeTransaction(f.context, prepared)
            assertThat(f.countMembers()).isEqualTo(1)
        }

    @Test
    fun `untrusted database roles cannot execute the wrapper directly`() =
        withFixture { f ->
            f.connection().use { database ->
                JdbcOperations.execute(database, "SET ROLE authenticated")
                assertFailsWith<java.sql.SQLException> {
                    JdbcOperations.execute(
                        database,
                        """SELECT public."pgPersistenceExecuteTransaction"('untrusted', '{}'::jsonb)""",
                    )
                }
            }
            assertThat(f.countMembers()).isEqualTo(0)
        }

    @Test
    fun `wrapper preserves invoker RLS rather than executing as its owner`() =
        withFixture { f ->
            val transaction = f.client().beginTransaction(f.context, "rls")
            transaction.insert(ENTITY, values())
            val request = Json.parseToJsonElement(transaction.prepare().encode()).jsonObject.getValue("request")
            f.connection().use { database ->
                JdbcOperations.execute(
                    database,
                    """
                    GRANT USAGE ON SCHEMA graphql TO persistence_executor;
                    GRANT SELECT, INSERT ON public.retry_members TO persistence_executor;
                    ALTER TABLE public.retry_members ENABLE ROW LEVEL SECURITY;
                    """.trimIndent(),
                )
                JdbcOperations.execute(database, "GRANT persistence_executor TO postgres WITH SET TRUE")
                JdbcOperations.execute(database, "SET ROLE persistence_executor")
                JdbcOperations.execute(
                    database,
                    "select set_config('request.headers', ?, false)",
                    """{"x-pg-persistence-scope":"trusted"}""",
                )
                JdbcOperations.query(
                    database,
                    """SELECT public."pgPersistenceExecuteTransaction"('rls', ?::jsonb)""",
                    { rows ->
                        check(rows.next())
                        assertThat(Json.parseToJsonElement(rows.getString(1)).jsonObject["status"].toString())
                            .isEqualTo("\"rolledBack\"")
                    },
                    request.toString(),
                )
            }
            assertThat(listOf(f.countMembers(), f.countRecords())).isEqualTo(listOf(0, 0))
        }

    @Test
    fun `lost response after commit recovers without rerunning the lambda or inserts`() =
        withFixture { f ->
            val responses = AtomicInteger()
            val client =
                f.client(
                    afterResponse = { if (responses.incrementAndGet() == 1) throw IOException("response lost") },
                )
            var invocations = 0
            val committed =
                client.transaction(f.context, "lost-response") {
                    invocations++
                    insert(ENTITY, values())
                }
            assertThat(listOf(invocations, f.countMembers(), f.countRecords(), responses.get()))
                .isEqualTo(listOf(1, 1, 1, 2))
            assertThat(committed.result[committed.value]).isNotNull()
        }

    @Test
    fun `a serialized prepared request can resume in a new client`() =
        withFixture { f ->
            val first = f.client().beginTransaction(f.context, "restart")
            val operation = first.insert(ENTITY, values())
            val saved = first.prepare().encode()
            val client = f.client()
            val prepared = DbPreparedTransaction.decode(saved)
            val initial = client.resumeTransaction(f.context, prepared)
            val replay = client.resumeTransaction(f.context, prepared)
            assertThat(initial[operation]).isEqualTo(replay[operation])
            assertThat(f.countMembers()).isEqualTo(1)
        }

    @Test
    fun `concurrent matching requests execute only once`() =
        withFixture { f ->
            val transaction = f.client().beginTransaction(f.context, "concurrent")
            val operation = transaction.insert(ENTITY, values())
            val prepared = transaction.prepare()
            val clients = List(2) { f.client() }
            val results =
                clients
                    .map { client ->
                        async(Dispatchers.IO) { client.resumeTransaction(f.context, prepared)[operation] }
                    }.awaitAll()
            assertThat(results.distinct().size).isEqualTo(1)
            assertThat(f.countMembers()).isEqualTo(1)
        }

    @Test
    fun `reusing an operation ID with different commands is rejected`() =
        withFixture { f ->
            val client = f.client()
            client.transaction(f.context, "mismatch") { insert(ENTITY, values()) }
            val error =
                assertFailsWith<DbTransactionException> {
                    client.transaction(f.context, "mismatch") { insert(ENTITY, values()) }
                }
            assertThat(error.outcome).isEqualTo(DbTransactionOutcome.REJECTED)
            assertThat(f.countMembers()).isEqualTo(1)
        }

    @Test
    fun `graphql failure rolls back previous writes and saves no success record`() =
        withFixture { f ->
            val same = values()
            assertFailsWith<UpstreamGraphqlException> {
                f.client().transaction(f.context, "rollback") {
                    insert(ENTITY, same)
                    insert(ENTITY, same)
                }
            }
            assertThat(listOf(f.countMembers(), f.countRecords())).isEqualTo(listOf(0, 0))
        }

    @Test
    fun `lookup is scoped and missing records do not claim rollback`() =
        withFixture { f ->
            val client = f.client()
            client.transaction(f.context, "lookup") { insert(ENTITY, values()) }
            assertThat(client.lookupTransaction(f.context, "lookup")).isNotNull()
            assertThat(f.client(scope = "tenant-2:user-1").lookupTransaction(f.context, "lookup")).isNull()
        }

    @Test
    fun `a prepared request cannot be resumed under another scope`() =
        withFixture { f ->
            val transaction = f.client().beginTransaction(f.context, "scope")
            transaction.insert(ENTITY, values())
            val prepared = transaction.prepare()
            assertFailsWith<IllegalArgumentException> {
                f.client(scope = "different").resumeTransaction(f.context, prepared)
            }
            assertThat(f.countMembers()).isEqualTo(0)
        }

    @Test
    fun `retry exhaustion reports unknown outcome and provides recoverable request`() =
        withFixture { f ->
            val client = f.client(attempts = 1, afterResponse = { throw IOException("response lost") })
            val error =
                assertFailsWith<DbTransactionException> {
                    client.transaction(f.context, "unknown") { insert(ENTITY, values()) }
                }
            assertThat(error.outcome).isEqualTo(DbTransactionOutcome.UNKNOWN)
            f.client().resumeTransaction(f.context, error.prepared)
            assertThat(f.countMembers()).isEqualTo(1)
        }

    private fun values() = PgGraphqlObject.of("uuidId" to UUID.randomUUID().toString(), "name" to "Guest")

    private fun withFixture(test: suspend kotlinx.coroutines.CoroutineScope.(RetryableTransactionFixture) -> Unit) =
        RetryableTransactionFixture().use { f -> runBlocking { test(f) } }

    companion object {
        private val ENTITY = PgGraphqlEntity("RetryMember")
    }
}
