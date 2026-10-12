@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import dev.viaduct.persistence.postgresql.delegate.GeneratedDelegateFixture.Companion.withFixture
import kotlinx.coroutines.withTimeout
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.types.NodeObject
import viaduct.engine.api.EngineObject
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.runFeatureTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedDelegateExecutionTest {
    @Test
    fun `generated delegates resolve aliased nested data through the real engine`() =
        withFixture { f ->
            val manager = f.insert(f.person("manager"))
            val person = f.insert(f.person("alice", manager))
            assertEquals(
                listOf(
                    mapOf(
                        "person" to mapOf("label" to "alice", "manager" to mapOf("label" to "manager")),
                        "healthy" to "ok",
                    ),
                    emptyList<Any>(),
                ),
                execute(f, f.id(person), "{ person { label: username manager { label: username } } healthy }"),
            )
        }

    @Test
    fun `checker denial preserves error paths siblings and nonnull propagation`() =
        withFixture { f ->
            val person = f.insert(f.person("alice"))
            assertEquals(
                listOf(mapOf("subject" to null, "healthy" to "ok"), listOf(listOf("subject", "label"))),
                execute(f, f.id(person), "{ subject: person { label: username } healthy }", denied = true),
            )
        }

    @Test
    fun `missing nodes preserve sibling data and the root error path`() =
        withFixture { f ->
            val id =
                f.context.globalIDFor(
                    f.types.getValue("Person"),
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                )
            assertEquals(
                listOf(mapOf("person" to null, "healthy" to "ok"), listOf(listOf("person"))),
                execute(f, id, "{ person { username } healthy }"),
            )
        }

    @Test
    fun `collection child checkers use indexed paths and preserve sibling results`() =
        withFixture { f ->
            val manager = f.insert(f.person("manager"))
            f.insert(f.person("alice", manager))
            assertEquals(
                listOf(mapOf("person" to null, "healthy" to "ok"), listOf(listOf("person", "reports", 0, "label"))),
                execute(f, f.id(manager), "{ person { reports { label: username } } healthy }", denied = true),
            )
        }

    internal suspend fun execute(
        f: GeneratedDelegateFixture,
        id: GlobalID<NodeObject>,
        query: String,
        denied: Boolean = false,
        deniedObject: Boolean = false,
    ): List<Any?> =
        withTimeout(10000) {
            var response: List<Any?>? = null
            val schema = f.internal.schema
            EngineTestModule(schema) {
                fieldWithValue("Query" to "healthy", "ok")
                field("Query" to if (id.type.name.startsWith("DelegateRecord")) "record" else "person") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            ctx.createNodeReference(
                                id.internalID,
                                checkNotNull(schema.schema.getObjectType(id.type.name)),
                            )
                        }
                    }
                }
                f.types.values.forEach { type ->
                    type(type.name) {
                        nodeUnbatchedExecutor(true) { internalId, selected, engineContext ->
                            val nodeId = f.context.globalIDFor(type, internalId)
                            val base = f.nodeContext(nodeId, checkNotNull(selected).printAsFieldSet())
                            val context =
                                object :
                                    SelectiveNodeExecutionContext<NodeObject> by base,
                                    InternalContext by f.internal {
                                    override fun <T : NodeObject> ref(id: GlobalID<T>): T {
                                        val reference =
                                            engineContext.createNodeReference(
                                                id.internalID,
                                                checkNotNull(schema.schema.getObjectType(id.type.name)),
                                            )
                                        val value =
                                            id.type.kcls.java
                                                .getConstructor(InternalContext::class.java, EngineObject::class.java)
                                                .newInstance(this, reference)
                                        return id.type.kcls.java
                                            .cast(value)
                                    }
                                }
                            f.access.fetch(f.client, context).__engineObject as EngineObjectData
                        }
                    }
                }
                field(f.types.getValue("Person").name to "username") {
                    checker { fn { _, _ -> check(!denied) { "Access denied" } } }
                }
                if (id.type.name.startsWith("DelegateRecord")) {
                    field("DelegateDocument${f.suffix}" to "label") {
                        checker { fn { _, _ -> check(!deniedObject) { "Access denied" } } }
                    }
                }
            }.runFeatureTest {
                val result = runQuery(query)
                response = listOf(result.getData<Map<String, Any?>>(), result.errors.map { it.path })
            }
            checkNotNull(response)
        }
}
