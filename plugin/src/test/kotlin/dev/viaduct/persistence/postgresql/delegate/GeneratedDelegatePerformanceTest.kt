@file:OptIn(viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import com.sun.management.ThreadMXBean
import dev.viaduct.persistence.orm.grt.GrtEntity
import dev.viaduct.persistence.postgresql.delegate.GeneratedDelegateFixture.Companion.withFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.hibernate.Hibernate
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.ObjectBase
import viaduct.api.types.NodeObject
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Opt-in measurement; elapsed time is reported, never asserted against a machine-specific budget. */
class GeneratedDelegatePerformanceTest {
    @Test
    fun `compare native entity hydration and ID projections with identical data`() =
        withFixture { f ->
            withTimeout(180000) {
                val parent = f.insert(f.person("manager"))
                insertPeople(f, 5000, parent)
                f.factory.statistics.isStatisticsEnabled = true
                val operations =
                    linkedMapOf<String, suspend () -> ObjectBase>(
                        "Collection: entity hydration" to { hydratedCollection(f, f.id(parent)) },
                        "Collection: ID projection" to {
                            f.access.fetch(f.client, f.nodeContext(f.id(parent), "reports { id }"))
                        },
                        "Connection: entity hydration" to { f.connection(mapOf("first" to 100)) },
                        "Connection: ID projection" to { f.connection(mapOf("first" to 100), identifiersOnly = true) },
                    )
                repeat(3) { operations.values.forEach { it() } }
                val samples = operations.keys.associateWith { mutableListOf<Sample>() }
                repeat(20) { iteration ->
                    // Alternate order to reduce systematic warm-cache/JIT bias.
                    val order = if (iteration % 2 == 0) operations.entries.toList() else operations.entries.reversed()
                    order.forEach { (name, operation) ->
                        samples.getValue(name).add(measure(f, operation))
                    }
                }
                writeReport(samples)
                val actual = operations.values.map { it() }
                assertEquals(
                    listOf(collectionIds(f, actual[0]), connectionData(f, actual[2])),
                    listOf(collectionIds(f, actual[1]), connectionData(f, actual[3])),
                )
            }
        }

    private suspend fun hydratedCollection(
        f: GeneratedDelegateFixture,
        parent: GlobalID<NodeObject>,
    ): ObjectBase =
        f.client.transaction(f.context) { session ->
            val entity = f.native(session, parent)
            val children = entity.javaClass.getMethod("getReports").invoke(entity) as List<*>
            val references =
                children.map {
                    val child = Hibernate.unproxy(it) as GrtEntity<*>
                    val id = checkNotNull(child.internalId).toString()
                    f.context.ref(f.context.globalIDFor(f.types.getValue("Person"), id))
                }
            f.builder("Person").put("reports", references).build() as ObjectBase
        }

    private suspend fun measure(
        f: GeneratedDelegateFixture,
        operation: suspend () -> ObjectBase,
    ): Sample =
        withContext(Dispatchers.IO) {
            val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
            val allocation =
                bean?.takeIf { it.isThreadAllocatedMemorySupported }?.also {
                    it.isThreadAllocatedMemoryEnabled = true
                }
            val thread = Thread.currentThread().threadId()
            f.factory.statistics.clear()
            val allocated = allocation?.getThreadAllocatedBytes(thread)
            val started = System.nanoTime()
            operation()
            val elapsed = System.nanoTime() - started
            check(Thread.currentThread().threadId() == thread) { "Allocation sample crossed threads" }
            Sample(
                elapsed / 1_000_000.0,
                allocated?.let { checkNotNull(allocation).getThreadAllocatedBytes(thread) - it },
                f.factory.statistics.entityLoadCount,
                f.factory.statistics.collectionLoadCount,
                f.factory.statistics.prepareStatementCount,
            )
        }

    private fun writeReport(samples: Map<String, List<Sample>>) {
        val rows =
            samples.map { (name, results) ->
                val time = results.map { it.milliseconds }.sorted()
                val bytes = results.mapNotNull { it.bytes?.toDouble() }.sorted()
                val allocated = if (bytes.isEmpty()) "unavailable" else format(bytes[bytes.size / 2] / 1024)
                val loads = results.map { it.entities }.distinct().joinToString(",")
                val collections = results.map { it.collections }.distinct().joinToString(",")
                val statements = results.map { it.statements }.distinct().joinToString(",")
                val timing = "${format(time[time.size / 2])} | ${format(time[18])} | $allocated"
                "| $name | $timing | $loads | $collections | $statements |"
            }
        val report =
            """
            # Local Hibernate delegate measurements

            PostgreSQL; 5,000 related Person rows; 100-row forward connection; Java ${System.getProperty("java.version")}.
            Three warmup rounds and 20 alternating-order samples per case. Each sample opens, initializes,
            reads, commits, and closes a fresh session against the same data. Setup and compilation are excluded.
            SQL statements include two transaction-local timeout settings. Allocation is measured on the IO
            thread with the JDK ThreadMXBean; it excludes server allocation and unrelated threads.

            | Operation | Median ms | p95 ms | Median allocated KiB | Entities loaded | Collections loaded | SQL statements |
            | --- | ---: | ---: | ---: | ---: | ---: | ---: |
            MEASURED_ROWS

            These are local exploratory measurements using Hibernate's test connection pool, small scalar
            payloads, and warm database caches. They do not establish production throughput or latency.
            Lists still materialize every reference. Connections fetch the requested page plus one row.
            """.trimIndent().replace("MEASURED_ROWS", rows.joinToString("\n"))
        File("build/reports/hibernate-delegates/performance.md").apply {
            java.nio.file.Files
                .createDirectories(parentFile.toPath())
            writeText(report + "\n")
        }
    }

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    @Suppress("UNCHECKED_CAST") // These fixtures use a generated List<Person> field.
    private fun collectionIds(f: GeneratedDelegateFixture, value: ObjectBase): List<GlobalID<NodeObject>> =
        (value.javaClass.getMethod("getReports").invoke(value) as List<ObjectBase>).map(f::id)

    private fun connectionData(
        f: GeneratedDelegateFixture,
        value: ObjectBase,
    ): List<Any?> {
        val nodes = value.get<List<ObjectBase>>("nodes", f.types.getValue("Person").kcls).map(f::id)
        val edges = value.javaClass.getMethod("getEdges").invoke(value) as List<*>
        val cursors = edges.map { checkNotNull(it).javaClass.getMethod("getCursor").invoke(it) }
        val page = checkNotNull(value.javaClass.getMethod("getPageInfo").invoke(value))
        val flags =
            listOf("HasNextPage", "HasPreviousPage", "StartCursor", "EndCursor").map {
                page.javaClass.getMethod("get$it").invoke(page)
            }
        return listOf(nodes, cursors, flags)
    }
}

private data class Sample(
    val milliseconds: Double,
    val bytes: Long?,
    val entities: Long,
    val collections: Long,
    val statements: Long,
)
