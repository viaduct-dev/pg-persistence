@file:OptIn(viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import dev.viaduct.persistence.postgresql.delegate.GeneratedDelegateFixture.Companion.withFixture
import viaduct.api.internal.ObjectBase
import kotlin.test.Test
import kotlin.test.assertEquals

class BuilderPropertyAccessTest {
    @Test
    fun `shared property access hydrates and dirty checks a GRT without generated scalar bean methods`() =
        withFixture(builderAccess = true) { f ->
            val saved = f.insert(f.person("alice"))
            val id = f.id(saved)
            val old =
                f.client.transaction(f.context) { session ->
                    val current = f.access.find(f.client, f.context, session, id)
                    val updated = GrtDelegate.toBuilder(current).put("username", "bob").build() as ObjectBase
                    f.access.update(f.client, f.context, session, updated)
                    current
                }
            val loaded = f.client.transaction(f.context) { f.access.find(f.client, f.context, it, id) }
            f.factory.statistics.isStatisticsEnabled = true
            f.factory.statistics.clear()
            val beanMethods =
                f.client.transaction(f.context) { session ->
                    val entity = f.native(session, id)
                    session.flush()
                    entity.javaClass.methods.any { it.name == "getUsername" || it.name == "setUsername" }
                }
            assertEquals(
                listOf("alice", "bob", false, true, 0L),
                listOf(
                    username(old),
                    username(loaded),
                    beanMethods,
                    f.schemaUnchanged,
                    f.factory.statistics.entityUpdateCount,
                ),
            )
        }

    @Test
    fun `shared builder hydration leaves selection aware SQL narrow and detached`() =
        withFixture(builderAccess = true) { f ->
            val id = f.id(f.insert(f.person("alice")))
            f.factory.statistics.isStatisticsEnabled = true
            f.factory.statistics.clear()
            f.clearStatements()
            val loaded = f.access.fetch(f.client, f.nodeContext(id, "username"))
            val sql = f.selectStatements().joinToString(" ")
            assertEquals(
                listOf("alice", 0L, false, false),
                listOf(username(loaded), f.factory.statistics.entityLoadCount, "nickname" in sql, "status" in sql),
            )
        }

    private fun username(value: ObjectBase): Any? = value.javaClass.getMethod("getUsername").invoke(value)
}
