@file:OptIn(viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.postgresql.HibernateRuntimeFixture.Companion.withFixture
import viaduct.api.globalid.GlobalID
import viaduct.api.types.NodeObject
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.runFeatureTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exercises Viaduct execution, including aliases, nested node resolution, and checker failures. */
class HibernateExecutionIntegrationTest {
    @Test
    fun `graphql aliases and nested references complete identically through both providers`() =
        withFixture { f ->
            val manager = f.insert("manager")
            val id = f.insert("alice")
            f.update(id, f.input("managerId" to manager))
            val query = "{ person { alias: username manager { username } } healthy }"
            assertEquals(execute(f, id, query, pg = true), execute(f, id, query, pg = false))
        }

    @Test
    fun `checker denial retains the public path and nonnull bubbling`() =
        withFixture { f ->
            val id = f.insert("alice")
            val query = "{ subject: person { label: username } healthy }"
            assertEquals(
                listOf(mapOf("subject" to null, "healthy" to "ok"), listOf(listOf("subject", "label"))),
                execute(f, id, query, pg = false, denied = true),
            )
        }

    @Test
    fun `missing nodes retain sibling data and the same GraphQL error path`() =
        withFixture { f ->
            val id = f.id()
            val query = "{ subject: person { username } healthy }"
            assertEquals(execute(f, id, query, pg = true), execute(f, id, query, pg = false))
        }

    private suspend fun execute(
        f: HibernateRuntimeFixture,
        id: GlobalID<NodeObject>,
        query: String,
        pg: Boolean,
        denied: Boolean = false,
    ): List<Any?> {
        var response: List<Any?>? = null
        EngineTestModule(f.schema) {
            fieldWithValue("Query" to "healthy", "ok")
            field("Query" to "person") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference(id.internalID, checkNotNull(schema.schema.getObjectType(id.type.name)))
                    }
                }
            }
            type(id.type.name) {
                nodeUnbatchedExecutor(true) { internalId, selections, _ ->
                    val selected = checkNotNull(selections).printAsFieldSet()
                    val nodeId = f.id(internalId)
                    val node = if (pg) f.pg(nodeId, selected) else f.orm(nodeId, selected)
                    node.__engineObject as EngineObjectData
                }
            }
            field(id.type.name to "username") {
                checker { fn { _, _ -> check(!denied) { "Access denied" } } }
            }
        }.runFeatureTest {
            val result = runQuery(query)
            response = listOf(result.getData<Map<String, Any?>>(), result.errors.map { it.path })
        }
        return checkNotNull(response)
    }
}
