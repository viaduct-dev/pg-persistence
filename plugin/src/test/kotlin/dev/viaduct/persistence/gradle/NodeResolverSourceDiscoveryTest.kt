package dev.viaduct.persistence.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NodeResolverSourceDiscoveryTest {
    @Test
    fun `discovers annotated node resolvers and batching with import aliases`() {
        val source =
            """
            package example
            import viaduct.api.resolver.Resolver as Resolve
            import example.resolverbases.NodeResolvers as Nodes
            import example.resolverbases.NodeResolvers.Person as PersonBase
            @Resolve class GroupResolver : Nodes.Group() {
                override suspend fun batchResolve(contexts: List<Context>) = TODO()
            }
            @Resolve class PersonResolver : PersonBase() {
                override suspend fun resolve(ctx: Context) = TODO()
            }
            """.trimIndent()
        assertEquals(mapOf("Group" to true, "Person" to false), discover(source))
    }

    @Test
    fun `ignores comments strings field resolvers and unannotated classes`() {
        val source =
            """
            import viaduct.api.resolver.Resolver
            import example.resolverbases.NodeResolvers
            // @Resolver class Fake : NodeResolvers.Fake()
            val text = "@Resolver class Fake : NodeResolvers.Fake()"
            class NotRegistered : NodeResolvers.NotRegistered()
            @Resolver class FieldResolver : QueryResolvers.Groups()
            """.trimIndent()
        assertEquals(emptyMap(), discover(source))
    }

    @Test
    fun `follows source inheritance and fully qualified names`() {
        val base =
            """
            package example
            abstract class Base : example.resolverbases.NodeResolvers.Group() {
                override suspend fun batchResolve(contexts: List<Context>) = TODO()
            }
            """.trimIndent()
        val resolver =
            """
            package other
            import example.Base
            @viaduct.api.resolver.Resolver class GroupResolver : Base()
            """.trimIndent()
        assertEquals(
            mapOf("Group" to true),
            NodeResolverSourceDiscovery.discover(mapOf("Base.kt" to base, "Group.kt" to resolver)),
        )
    }

    @Test
    fun `rejects duplicate implementations for the same node`() {
        assertFailsWith<IllegalArgumentException> {
            discover(
                """
                import viaduct.api.resolver.Resolver
                import example.resolverbases.NodeResolvers
                @Resolver class First : NodeResolvers.Group()
                @Resolver class Second : NodeResolvers.Group()
                """.trimIndent(),
            )
        }
    }

    private fun discover(source: String) = NodeResolverSourceDiscovery.discover(mapOf("Resolvers.kt" to source))
}
