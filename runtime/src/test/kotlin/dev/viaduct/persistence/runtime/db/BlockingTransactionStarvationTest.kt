package dev.viaduct.persistence.runtime.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class BlockingTransactionStarvationTest {
    @Test
    fun `saturated IO callers complete without blocking their callbacks`() {
        val process =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dkotlinx.coroutines.io.parallelism=1",
                "-cp",
                System.getProperty("transactionProbeClasspath"),
                BlockingTransactionProbe::class.java.name,
            ).redirectErrorStream(true).start()
        try {
            val completed = process.waitFor(20, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            assertEquals(
                "completed=32",
                if (completed &&
                    process.exitValue() == 0
                ) {
                    process.inputStream
                        .bufferedReader()
                        .readText()
                        .trim()
                } else {
                    "probe stalled or failed"
                },
            )
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

object BlockingTransactionProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val adapter =
            object : BlockingDbTransactions(2) {
                override fun <T> executeBlocking(
                    headers: Map<String, String>,
                    block: DbTransactionScope.() -> T,
                ): DbTransactionCommit<T> =
                    executeImmediateTransaction(
                        {
                            val payload = Json.parseToJsonElement("""{"operation0":{"affectedCount":1,"records":[]}}""")
                            DbResult(payload.jsonObject)
                        },
                        block,
                    )
            }
        adapter.use {
            val completed =
                runBlocking {
                    (1..32)
                        .map {
                            async(Dispatchers.IO) {
                                adapter.execute(emptyMap()) {
                                    yield()
                                    insert(PgGraphqlEntity("Member"), PgGraphqlObject.of("name" to "Member"))
                                }
                            }
                        }.awaitAll()
                        .size
                }
            println("completed=$completed")
        }
    }
}
