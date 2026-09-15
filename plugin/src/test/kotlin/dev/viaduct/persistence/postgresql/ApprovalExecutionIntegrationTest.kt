@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.postgresql.ApprovalRequestFixture.Companion.withFixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.api.globalid.GlobalID
import viaduct.api.types.NodeObject
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.runFeatureTest
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalExecutionIntegrationTest {
    @Test
    fun `engine completes inherited interface fields and mixed connection nodes`() =
        withFixture { f ->
            runBlocking {
                val imported = f.createRequest("ImportRequest", "teamName", "Engineering")
                val access = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                f.assign(imported)
                f.change(imported, "reviewable")
                f.addTo(
                    "reviewQueue",
                    imported,
                    dev.viaduct.persistence.runtime.db.PgGraphqlObject
                        .of("uuidId" to ROW_ONE),
                )
                f.addTo(
                    "reviewQueue",
                    access,
                    dev.viaduct.persistence.runtime.db.PgGraphqlObject
                        .of("uuidId" to ROW_TWO),
                )
                engine(f, imported).runFeatureTest {
                    runQuery(
                        """{ review(id: "${f.assignmentId}") {
                    reviewable { ... on Summarized${f.suffix} { title: summary } }
                    reviewQueue { nodes {
                        ... on ${imported.type.name} { teamName }
                        ... on ${access.type.name} { requestedPermission }
                    } }
                } }""",
                    ).assertJson(
                        """{"data":{"review":{
                    "reviewable":{"title":"Review Engineering"},
                    "reviewQueue":{"nodes":[{"teamName":"Engineering"},{"requestedPermission":"EDITOR"}]}
                }}}""",
                    )
                }
            }
        }

    @Test
    fun `engine completes an aliased union from a database backed resolver`() =
        withFixture { f ->
            runBlocking {
                val request = f.createRequest("ImportRequest", "teamName", "Engineering")
                f.assign(request)
                engine(f, request).runFeatureTest {
                    runQuery(
                        """{ review(id: "${f.assignmentId}") {
                    subject: request { __typename ... on ${request.type.name} { label: teamName } }
                } }""",
                    ).assertJson(
                        """{"data":{"review":{"subject":{
                    "__typename":"${request.type.name}","label":"Engineering"
                }}}}""",
                    )
                }
            }
        }

    @Test
    fun `engine resolves a concrete node behind a union using client selections`() =
        withFixture { f ->
            runBlocking {
                val request = f.createRequest("ImportRequest", "teamName", "Engineering")
                engine(f, request).runFeatureTest {
                    runQuery("""{ request { __typename ... on ${request.type.name} { teamName summary } } }""")
                        .assertJson(
                            """{"data":{"request":{
                        "__typename":"${request.type.name}","teamName":"Engineering","summary":"Review Engineering"
                    }}}""",
                        )
                }
            }
        }

    @Test
    fun `missing database row becomes a GraphQL error without losing sibling data`() =
        withFixture { f ->
            runBlocking {
                val request = f.createRequest("ImportRequest", "teamName", "Engineering")
                engine(f, request).runFeatureTest {
                    val result = runQuery("""{ missing: review(id: "${UUID.randomUUID()}") { id } healthy }""")
                    assertEquals(mapOf("missing" to null, "healthy" to "ok"), result.getData())
                    assertEquals(listOf("missing"), result.errors.single().path)
                    assertContains(result.errors.single().message, "Db response did not include")
                }
            }
        }

    @Test
    fun `checker denial uses public aliases and normal non-null bubbling`() =
        withFixture { f ->
            runBlocking {
                val request = f.createRequest("ImportRequest", "teamName", "Engineering")
                f.assign(request)
                var checked = false
                engine(f, request) {
                    checked = true
                    error("Review permission denied")
                }.runFeatureTest {
                    val result =
                        runQuery(
                            """{ review(id: "${f.assignmentId}") {
                    subject: request { ... on ${request.type.name} { label: teamName } }
                } healthy }""",
                        )
                    assertEquals(mapOf("review" to null, "healthy" to "ok"), result.getData())
                    assertEquals(listOf("review", "subject", "label"), result.errors.single().path)
                    assertTrue(checked)
                }
            }
        }

    @Test
    fun `allowing checker runs for concrete fields inside the union`() =
        withFixture { f ->
            runBlocking {
                val request = f.createRequest("ImportRequest", "teamName", "Engineering")
                f.assign(request)
                var checked = false
                engine(f, request) { checked = true }.runFeatureTest {
                    runQuery(
                        """{ review(id: "${f.assignmentId}") {
                    request { ... on ${request.type.name} { teamName } }
                } }""",
                    ).assertJson("""{"data":{"review":{"request":{"teamName":"Engineering"}}}}""")
                    assertTrue(checked)
                }
            }
        }

    private fun engine(
        f: ApprovalRequestFixture,
        request: GlobalID<NodeObject>,
        check: () -> Unit = {},
    ) = EngineTestModule(f.schema) {
        fieldWithValue("Query" to "healthy", "ok")
        field("Query" to "review") {
            resolverExecutor {
                MockFieldUnbatchedResolverExecutor(
                    isSelective = true,
                    resolverId = "review",
                ) { args, _, _, selections, _ ->
                    val fields = requireNotNull(selections).printAsFieldSet()
                    f.readOwner(fields, args.getValue("id") as String).__engineObject
                }
            }
        }
        field("Query" to "request") {
            resolver {
                fn { _, _, _, _, ctx ->
                    val concrete = requireNotNull(schema.schema.getObjectType(request.type.name))
                    ctx.createNodeReference(request.internalID, concrete)
                }
            }
        }
        type(request.type.name) {
            nodeUnbatchedExecutor(true) { id, selections, _ ->
                val fields = requireNotNull(selections).printAsFieldSet()
                f.readRoot(GlobalID(request.type, id), "ApprovalRequest", fields).__engineObject as EngineObjectData
            }
        }
        field(request.type.name to "teamName") {
            checker { fn { _, _ -> check() } }
        }
    }
}
