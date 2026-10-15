@file:Suppress("InjectDispatcher") // Real blocking JDBC concurrency requires IO worker threads.

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.runtime.db.PgGraphqlEntity
import dev.viaduct.persistence.runtime.db.PgGraphqlObject
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class CrossSchemaRetryableTransactionTest {
    @Test
    fun `lost response recovers both schema writes without repeating application work`() =
        RetryableTransactionFixture(splitSchemas = true).use { fixture ->
            runBlocking {
                val responses = AtomicInteger()
                val client =
                    fixture.client(afterResponse = {
                        if (responses.incrementAndGet() == 1) throw IOException("Lost mixed-schema response")
                    })
                val id = UUID.randomUUID().toString()
                var calls = 0
                val result =
                    client.transaction(fixture.context, "mixed-lost-response") {
                        calls++
                        insert(PgGraphqlEntity("RetryMember"), member(id))
                        insert(PgGraphqlEntity("RetryPurchase"), purchase(id))
                    }
                assertNotNull(result.result[result.value])
                assertEquals(
                    listOf(1, 1, 1, 1, 2),
                    listOf(
                        calls,
                        fixture.countMembers(),
                        fixture.countPurchases(),
                        fixture.countRecords(),
                        responses.get(),
                    ),
                )
            }
        }

    @Test
    fun `late failure in a buffered transaction rolls back both schemas`() =
        RetryableTransactionFixture(splitSchemas = true).use { fixture ->
            runBlocking {
                val id = UUID.randomUUID().toString()
                assertFailsWith<UpstreamGraphqlException> {
                    fixture.client().transaction(fixture.context, "mixed-rollback") {
                        insert(PgGraphqlEntity("RetryMember"), member(id))
                        insert(PgGraphqlEntity("RetryPurchase"), purchase(id))
                        insert(PgGraphqlEntity("RetryMember"), member(id))
                    }
                }
                assertEquals(
                    listOf(0, 0, 0),
                    listOf(fixture.countMembers(), fixture.countPurchases(), fixture.countRecords()),
                )
            }
        }

    @Test
    fun `concurrent resume of the same cross-schema operation commits once`() =
        RetryableTransactionFixture(splitSchemas = true).use { fixture ->
            runBlocking {
                val id = UUID.randomUUID().toString()
                val transaction = fixture.client().beginTransaction(fixture.context, "mixed-concurrent")
                transaction.insert(PgGraphqlEntity("RetryMember"), member(id))
                val inserted = transaction.insert(PgGraphqlEntity("RetryPurchase"), purchase(id))
                val prepared = transaction.prepare()
                val clients = List(2) { fixture.client() }
                val results =
                    clients
                        .map { client ->
                            async(Dispatchers.IO) { client.resumeTransaction(fixture.context, prepared)[inserted] }
                        }.awaitAll()
                assertNotNull(results.first())
                assertEquals(1, results.distinct().size)
                assertEquals(
                    listOf(1, 1, 1),
                    listOf(fixture.countMembers(), fixture.countPurchases(), fixture.countRecords()),
                )
            }
        }

    private fun member(id: String) = PgGraphqlObject.of("uuidId" to id, "name" to "Buyer")

    private fun purchase(id: String) = PgGraphqlObject.of("uuidId" to id, "label" to "Order", "buyerId" to id)
}
