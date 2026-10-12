@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import dev.viaduct.persistence.postgresql.delegate.GeneratedDelegateFixture.Companion.withFixture
import viaduct.api.internal.ObjectBase
import kotlin.test.Test
import kotlin.test.assertEquals

/** Inspect real PostgreSQL SELECTs: omitting fields from the result alone does not prove selective hydration. */
class GeneratedDelegateSelectionTest {
    @Test
    fun `a selective scalar read loads only identity and the requested column`() =
        withFixture { f ->
            val saved = f.insert(f.person("alice"))
            f.clearStatements()
            f.factory.statistics.isStatisticsEnabled = true
            f.factory.statistics.clear()
            val value = f.access.fetch(f.client, f.nodeContext(f.id(saved), "username"))
            assertEquals(
                listOf("alice", listOf(setOf("_uuid_id", "username")), 0L),
                listOf(
                    get(value, "Username"),
                    f.selectStatements().map(::columns),
                    f.factory.statistics.entityLoadCount,
                ),
            )
        }

    @Test
    fun `batch contexts with different fields do not hydrate a union of their columns`() =
        withFixture { f ->
            val saved = f.insert(f.person("alice"))
            val name = f.nodeContext(f.id(saved), "username")
            val nickname = f.nodeContext(f.id(saved), "nickname")
            f.clearStatements()
            val result = f.access.batch(f.client, listOf(name, nickname))
            assertEquals(
                listOf("alice", "initial", setOf(setOf("_uuid_id", "username"), setOf("_uuid_id", "nickname"))),
                listOf(
                    get(result.getValue(name).get(), "Username"),
                    get(result.getValue(nickname).get(), "Nickname"),
                    f.selectStatements().map(::columns).toSet(),
                ),
            )
        }

    @Test
    fun `a relationship read selects its FK without hydrating either entity`() =
        withFixture { f ->
            val manager = f.insert(f.person("manager"))
            val saved = f.insert(f.person("alice", manager))
            f.clearStatements()
            f.factory.statistics.isStatisticsEnabled = true
            f.factory.statistics.clear()
            val selected = f.access.fetch(f.client, f.nodeContext(f.id(saved), "manager { id }"))
            assertEquals(
                listOf(f.id(manager), listOf(setOf("_uuid_id", "manager_id")), 0L),
                listOf(
                    f.id(get(selected, "Manager") as ObjectBase),
                    f.selectStatements().map(::columns),
                    f.factory.statistics.entityLoadCount,
                ),
            )
        }

    @Test
    fun `typename only still checks existence without selecting scalar properties`() =
        withFixture { f ->
            val saved = f.insert(f.person("alice"))
            f.clearStatements()
            val selected = f.access.fetch(f.client, f.nodeContext(f.id(saved), "__typename"))
            assertEquals(
                listOf(saved.javaClass, listOf(setOf("_uuid_id")), false),
                listOf(
                    selected.javaClass,
                    f.selectStatements().map(::columns),
                    runCatching { get(selected, "Username") }.isSuccess,
                ),
            )
        }

    @Test
    fun `compatible batch contexts share one column projection`() =
        withFixture { f ->
            val first = f.insert(f.person("alice"))
            val second = f.insert(f.person("bob"))
            val contexts = listOf(first, second).map { f.nodeContext(f.id(it), "username") }
            f.clearStatements()
            val results = f.access.batch(f.client, contexts)
            assertEquals(
                listOf(listOf("alice", "bob"), listOf(setOf("_uuid_id", "username"))),
                listOf(
                    contexts.map { get(results.getValue(it).get(), "Username") },
                    f.selectStatements().map(::columns),
                ),
            )
        }

    @Test
    fun `ordinary object lists share a column projection and leave unrelated fields unloaded`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val parent = f.insert(GeneratedDelegateObjectTest.document(f, "parent"))
            repeat(5) { f.insert(GeneratedDelegateObjectTest.document(f, "child-$it", parent)) }
            val record = f.insert(GeneratedDelegateObjectTest.record(f, person, document = parent))
            f.clearStatements()
            val selected = f.access.fetch(f.client, f.nodeContext(f.id(record), "document { children { label } }"))
            val document = get(selected, "Document") as ObjectBase
            val children = get(document, "Children") as List<*>
            val columns = f.selectStatements().map(::columns)
            assertEquals(
                listOf((0..4).map { "child-$it" }, 4, false),
                listOf(
                    children.map { get(it as ObjectBase, "Label") }.sortedBy { it.toString() },
                    columns.size,
                    columns.any { "value" in it },
                ),
            )
        }

    @Test
    fun `real GraphQL execution projects scalar columns separately for parent and child`() =
        withFixture { f ->
            val manager = f.insert(f.person("manager"))
            val saved = f.insert(f.person("alice", manager))
            f.clearStatements()
            val result =
                GeneratedDelegateExecutionTest().execute(
                    f,
                    f.id(saved),
                    "{ person { label: username manager { label: username } } healthy }",
                )
            assertEquals(
                listOf(
                    listOf(
                        mapOf(
                            "person" to mapOf("label" to "alice", "manager" to mapOf("label" to "manager")),
                            "healthy" to "ok",
                        ),
                        emptyList<Any>(),
                    ),
                    listOf(setOf("_uuid_id", "username", "manager_id"), setOf("_uuid_id", "username")),
                ),
                listOf(result, f.selectStatements().map(::columns)),
            )
        }

    @Test
    fun `nested ordinary objects project only selected fields through a finite property tree`() =
        withFixture(extended = true) { f ->
            val person = f.insert(f.person("alice"))
            val parent = f.insert(GeneratedDelegateObjectTest.document(f, "parent"))
            val child = f.insert(GeneratedDelegateObjectTest.document(f, "child", parent))
            val record = f.insert(GeneratedDelegateObjectTest.record(f, person, document = child))
            f.clearStatements()
            val selected = f.access.fetch(f.client, f.nodeContext(f.id(record), "document { label parent { label } }"))
            val document = get(selected, "Document") as ObjectBase
            val ancestor = get(document, "Parent") as ObjectBase
            val statements = f.selectStatements().map(::columns)
            assertEquals(
                listOf("child", "parent", false, false, false),
                listOf(
                    get(document, "Label"),
                    get(ancestor, "Label"),
                    statements.any { "value" in it },
                    statements.any { "username" in it },
                    statements.any { "nickname" in it },
                ),
            )
        }

    companion object {
        private fun get(
            value: ObjectBase,
            suffix: String,
        ): Any? = value.javaClass.getMethod("get$suffix").invoke(value)

        /** Hibernate qualifies physical column names; record only the SELECT list, not predicates. */
        fun columns(sql: String): Set<String> =
            Regex("[A-Za-z][A-Za-z0-9_]*\\.([A-Za-z_][A-Za-z0-9_]*)")
                .findAll(sql.substringBefore(" from "))
                .map { it.groupValues[1] }
                .toSet()
    }
}
