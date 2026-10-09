@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import dev.viaduct.persistence.postgresql.delegate.GeneratedDelegateFixture.Companion.withFixture
import kotlinx.coroutines.withTimeout
import org.hibernate.Hibernate
import viaduct.api.internal.ObjectBase
import viaduct.api.types.OffsetCursor
import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedDelegateLargeCollectionTest {
    @Test
    fun `large FK collection returns detached references without loading child entities or the native bag`() =
        withFixture { f ->
            val parent = f.insert(f.person("manager"))
            insertPeople(f, 1000, parent)
            f.factory.statistics.isStatisticsEnabled = true
            f.factory.statistics.clear()
            val loaded = f.access.fetch(f.client, f.nodeContext(f.id(parent), "reports { id }"))
            val reports = loaded.get<List<ObjectBase>>("reports", parent::class)
            assertEquals(
                listOf(1000, 1000, 1L, 0L),
                listOf(
                    reports.size.toLong(),
                    reports
                        .map(f::id)
                        .distinct()
                        .size
                        .toLong(),
                    f.factory.statistics.entityLoadCount,
                    f.factory.statistics.collectionLoadCount,
                ),
            )
        }

    @Test
    fun `ID projection pages use Viaduct cursors and load no entities across a large result`() =
        withFixture { f ->
            insertPeople(f, 1000)
            f.factory.statistics.isStatisticsEnabled = true
            f.factory.statistics.clear()
            val first = f.connection(mapOf("first" to 20), identifiersOnly = true)
            val second =
                f.connection(
                    mapOf("first" to 20, "after" to OffsetCursor.fromOffset(19).value),
                    identifiersOnly = true,
                )
            val backward = f.connection(mapOf("last" to 20), identifiersOnly = true)
            val ids =
                listOf(first, second, backward).map {
                    it.get<List<ObjectBase>>("nodes", f.types.getValue("Person").kcls).map(f::id)
                }
            assertEquals(
                listOf(20, 20, 20, 60, 0L, 0L),
                ids.map { it.size.toLong() } +
                    listOf(
                        ids
                            .flatten()
                            .distinct()
                            .size
                            .toLong(),
                        f.factory.statistics.entityLoadCount,
                        f.factory.statistics.collectionLoadCount,
                    ),
            )
        }

    @Test
    fun `relationship projections observe native writes before commit and keep the collection lazy`() =
        withFixture { f ->
            val parent = f.insert(f.person("manager"))
            val result =
                f.client.transaction(f.context) { session ->
                    val entity = f.native(session, f.id(parent))
                    val bag = entity.javaClass.getMethod("getReports").invoke(entity)
                    val child = f.access.insert(f.client, f.context, session, f.person("child", parent))
                    val context = f.nodeContext(f.id(parent), "reports { id }")
                    // Fetch through the generated typed adapter with this same session.
                    val method =
                        f.loader.loadClass(entity.javaClass.name).getMethod(
                            "selected",
                            viaduct.api.context.ResolverExecutionContext::class.java,
                            org.hibernate.Session::class.java,
                            Set::class.java,
                        )
                    val selected = method.invoke(entity, context, session, setOf("reports")) as ObjectBase
                    val ids = selected.get<List<ObjectBase>>("reports", child::class).map(f::id)
                    listOf(ids == listOf(f.id(child)), Hibernate.isInitialized(bag))
                }
            assertEquals(listOf(true, false), result)
        }
}

/** Data setup uses the actual generated delegates and normal Hibernate batching. */
internal suspend fun insertPeople(
    f: GeneratedDelegateFixture,
    count: Int,
    parent: ObjectBase? = null,
) = withTimeout(120000) {
    f.client.transaction(f.context) { session ->
        session.jdbcBatchSize = 50
        repeat(count) { index ->
            val name = "person-${index.toString().padStart(6, '0')}"
            f.access.insert(f.client, f.context, session, f.person(name, parent))
            if ((index + 1) % 50 == 0) {
                session.flush()
                session.clear()
            }
        }
    }
}
