@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.jdbc.JdbcOperations
import dev.viaduct.persistence.orm.HibernateClient
import dev.viaduct.persistence.postgresql.HibernateRuntimeFixture.Companion.withFixture
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.persister.entity.AbstractEntityPersister
import kotlin.test.Test
import kotlin.test.assertEquals

class HibernateAuthorizationIntegrationTest {
    @Test
    fun `native transaction local roles enforce RLS without leaking between calls`() =
        withFixture { f ->
            val alice = f.insert("alice")
            val bob = f.insert("bob")
            val role = "authenticated"
            val mappings = (f.factory as SessionFactoryImplementor).mappingMetamodel
            val entity = mappings.getEntityDescriptor(f.type().name) as AbstractEntityPersister
            val table = entity.tableName
            JdbcOperations.execute(f.database, "GRANT SELECT, INSERT, UPDATE, DELETE ON $table TO $role")
            JdbcOperations.execute(f.database, "ALTER TABLE $table ENABLE ROW LEVEL SECURITY")
            JdbcOperations.execute(
                f.database,
                "CREATE POLICY principal ON $table USING (username = current_setting('app.principal', true)) " +
                    "WITH CHECK (username = current_setting('app.principal', true))",
            )
            var principal = "alice"
            val client =
                HibernateClient(f.factory) { session, _ ->
                    session.doWork { db ->
                        JdbcOperations.execute(db, "SET LOCAL ROLE $role")
                        JdbcOperations.execute(db, "SELECT set_config('app.principal', ?, true)", principal)
                    }
                }

            suspend fun visible(): List<String> =
                client.transaction(f.ctx) { session ->
                    session
                        .createSelectionQuery("from ${f.type().name}", Any::class.java)
                        .resultList
                        .map { session.getIdentifier(it).toString() }
                }
            val first = visible()
            val denied =
                runCatching {
                    client.transaction(f.ctx) { session ->
                        session.persist(f.type().name, mutableMapOf("username" to "mallory"))
                    }
                }.exceptionOrNull()
            principal = "bob"
            val second = visible()
            assertEquals(
                listOf(listOf(alice.internalID), listOf(bob.internalID), true),
                listOf(first, second, denied != null),
            )
        }
}
