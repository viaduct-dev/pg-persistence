package com.example.groups

import dev.viaduct.persistence.jdbc.JdbcPgGraphqlExecutor
import dev.viaduct.persistence.jdbc.JdbcSchemaVisibility
import dev.viaduct.persistence.runtime.db.DbClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.postgresql.ds.PGSimpleDataSource
import viaduct.service.BasicViaductFactory
import viaduct.service.api.ExecutionInput
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(120)
class TenantSchemaExecutionTest {
    @Test
    fun `two tenants compose DbClient mutations and lookups with qualified persistence`() {
        val url = requireNotNull(System.getenv("PG_KAN37_GENERATED_URL"))
        require(url.matches(Regex("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/kan37_generated_tests")))
        val schemas = System.getProperty("tenantSchemas").split(",")
        require(schemas.size == 2 && schemas.all { it.matches(Regex("kan37_[a-z0-9_]+")) })
        val source = PGSimpleDataSource().apply {
            setURL(url)
            user = "postgres"
            password = System.getenv("PG_KAN37_GENERATED_PASSWORD") ?: "postgres"
        }
        try {
            source.connection.use { database ->
                database.createStatement().use { statement ->
                    statement.execute(requireNotNull(javaClass.getResource("/META-INF/schema-create.sql")).readText())
                }
            }
            val db = DbClient(JdbcPgGraphqlExecutor(source, JdbcSchemaVisibility(schemas + "public")))
            val service = BasicViaductFactory.create(SharedTenantModuleInjectorFactory(object : CodeInjector {
                override fun <T> getProvider(clazz: Class<T>): Provider<T> = Provider {
                    clazz.cast(clazz.constructors.single().newInstance(db))
                }
            }))
            fun execute(document: String) = runBlocking { service.execute(ExecutionInput.create(document)) }
            val created = execute("""mutation { createPurchase(name: "Chess") { id name } }""")
            assertTrue(created.errors.isEmpty(), created.errors.toString())
            val group = (created.getData() as Map<*, *>)["createPurchase"] as Map<*, *>
            assertEquals("Chess", group["name"])
            val result = execute("""{ lookupPurchases(label: "Chess", first: 1) {
                edges { cursor node { label buyer { id name } } }
                pageInfo { hasNextPage }
            } }""")
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            val connection = (result.getData() as Map<*, *>)["lookupPurchases"] as Map<*, *>
            val edges = connection["edges"] as List<*>
            assertEquals(1, edges.size)
            val edge = edges.single() as Map<*, *>
            assertEquals(viaduct.api.types.OffsetCursor.fromOffset(0).value, edge["cursor"])
            assertEquals(mapOf("label" to "Chess", "buyer" to group), edge["node"])
            assertEquals(mapOf("hasNextPage" to false), connection["pageInfo"])
            val failed = execute("""mutation { createPurchase(name: "Rollback", fail: true) { name } }""")
            assertTrue(failed.errors.isNotEmpty(), failed.toString())
            source.connection.use { database ->
                database.createStatement().use { statement ->
                    schemas.forEachIndexed { index, schema ->
                        val table = if (index == 0) "groups" else "purchases"
                        val column = if (index == 0) "name" else "label"
                        statement.executeQuery("SELECT count(*) FROM \"$schema\".\"$table\" WHERE $column = 'Rollback'").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(0, rows.getInt(1))
                        }
                    }
                }
            }
            val globalId = group["id"]
            val node = execute("""{ node(id: "$globalId") { ... on Group { id name } } }""")
            assertTrue(node.errors.isEmpty(), node.errors.toString())
            assertEquals(mapOf("node" to group), node.getData())
        } finally {
            source.connection.use { database ->
                database.createStatement().use { statement ->
                    schemas.reversed().forEach { statement.execute("DROP SCHEMA IF EXISTS \"$it\" CASCADE") }
                }
            }
        }
    }
}
