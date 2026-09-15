@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.postgresql.ApprovalRequestFixture.Companion.withFixture
import dev.viaduct.persistence.runtime.db.PgGraphqlEntity
import dev.viaduct.persistence.runtime.db.PgGraphqlObject
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.api.internal.ObjectBase
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ApprovalRelationshipIntegrationTest {
    @Test
    fun `switching between requests of the same type changes the ID`() =
        withFixture { f ->
            runBlocking {
                val first = f.createRequest("AccessRequest", "requestedPermission", "VIEWER")
                val second = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                f.assign(first)
                f.change(second)
                val request = f.readRequest()
                assertEquals(second.internalID, request.internalId())
                assertEquals("EDITOR", request.get<String>("requestedPermission", String::class))
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["optionalRequest", "reviewable"])
    fun `nullable reference can be absent populated and cleared without deleting the target`(field: String) =
        withFixture { f ->
            runBlocking {
                val request = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                f.assign(request)

                suspend fun optional(): ObjectBase? =
                    f
                        .readOwner("$field { ${f.requestSelection} }")
                        .getOrNull(field, f.field(field).type.kcls)
                assertNull(optional())
                f.change(request, field)
                assertEquals(request.internalID, optional()?.internalId())
                f.change(null, field)
                assertNull(optional())
                assertEquals(setOf(request.internalID), f.ids(PgGraphqlEntity(request.type.name)))
            }
        }

    @Test
    fun `deleting a referenced target fails and preserves its reference`() =
        withFixture { f ->
            runBlocking {
                val request = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                f.assign(request)
                assertFailsWith<UpstreamGraphqlException> {
                    f.delete(PgGraphqlEntity(request.type.name), request.internalID)
                }
                assertEquals(request.internalID, f.readRequest().internalId())
            }
        }

    @Test
    fun `deleting the owner leaves its target intact and then the target can be deleted`() =
        withFixture { f ->
            runBlocking {
                val request = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                f.assign(request)
                assertEquals(1, f.delete(f.assignment, f.assignmentId))
                assertEquals(emptySet(), f.ids(f.assignment))
                assertEquals(setOf(request.internalID), f.ids(PgGraphqlEntity(request.type.name)))
                assertEquals(1, f.delete(PgGraphqlEntity(request.type.name), request.internalID))
                assertEquals(emptySet(), f.ids(PgGraphqlEntity(request.type.name)))
            }
        }

    @Test
    fun `inherited interface fields and concrete fragments become generated GRT fields`() =
        withFixture { f ->
            runBlocking {
                val request = f.createRequest("ImportRequest", "teamName", "Engineering")
                f.assign(request)
                f.change(request, "reviewable")
                val owner =
                    f.readOwner(
                        """reviewable {
                ... on Summarized${f.suffix} { summary }
                ... on ImportRequest${f.suffix} { id teamName }
            }""",
                    )
                val result = owner.get<ObjectBase>("reviewable", f.field("reviewable").type.kcls)
                assertEquals(request.internalID, result.internalId())
                assertEquals("Review Engineering", result.get<String>("summary", String::class))
                assertEquals("Engineering", result.get<String>("teamName", String::class))
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["ApprovalRequest", "Reviewable", "Summarized"])
    fun `abstract root reads the explicitly selected concrete table`(declared: String) =
        withFixture { f ->
            runBlocking {
                val request = f.createRequest("ImportRequest", "teamName", "Engineering")
                val result = f.readRoot(request, declared, "... on ImportRequest${f.suffix} { id teamName }")
                assertEquals(request.type.name, result.javaClass.simpleName)
                assertEquals(request.internalID, result.internalId())
                assertEquals("Engineering", result.get<String>("teamName", String::class))
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["requests", "reviewables"])
    fun `mixed list preserves every concrete GRT and an empty list is empty`(field: String) =
        withFixture { f ->
            runBlocking {
                val access = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                val imported = f.createRequest("ImportRequest", "teamName", "Engineering")
                val onboarding = f.createRequest("OnboardingRequest", "notificationEmail", "guest@example.test")
                f.assign(access)

                suspend fun requests() =
                    f
                        .readOwner("$field { ${f.requestSelection} }")
                        .get<List<ObjectBase>>(field, f.field(field).type.kcls)
                assertEquals(emptyList(), requests())
                listOf(access, imported, onboarding).forEach { f.addTo(field, it) }
                assertEquals(
                    setOf(access.internalID, imported.internalID, onboarding.internalID),
                    requests().map { it.internalId() }.toSet(),
                )
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["queue", "reviewQueue"])
    fun `connection preserves edge data and pagination`(field: String) =
        withFixture { f ->
            runBlocking {
                val access = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                val imported = f.createRequest("ImportRequest", "teamName", "Engineering")
                f.assign(access)
                f.addTo(field, access, PgGraphqlObject.of("uuidId" to ROW_ONE, "label" to "first"))
                f.addTo(field, imported, PgGraphqlObject.of("uuidId" to ROW_TWO, "label" to "second"))

                suspend fun edge(arguments: String): ObjectBase {
                    val queue =
                        f
                            .readOwner("$field$arguments { edges { cursor label node { ${f.requestSelection} } } }")
                            .get<ObjectBase>(field, f.field(field).type.kcls)
                    val edgeType = if (field == "queue") "ApprovalEdge" else "ReviewEdge"
                    return queue.get<List<ObjectBase>>("edges", f.reflection("$edgeType${f.suffix}").kcls).single()
                }
                val first = edge("(first: 1)")
                val after = first.get<String>("cursor", String::class)
                val second = edge("""(first: 1, after: "$after")""")
                val before = second.get<String>("cursor", String::class)
                val previous = edge("""(last: 1, before: "$before")""")

                fun ObjectBase.nodeId() = get<ObjectBase>("node", f.requestField.type.kcls).internalId()
                assertEquals(
                    listOf(access.internalID, imported.internalID, access.internalID),
                    listOf(first, second, previous).map { it.nodeId() },
                )
                val labels = listOf(first, second).map { it.get<String>("label", String::class) }
                assertEquals(listOf("first", "second"), labels)
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["queue", "reviewQueue"])
    fun `connection nodes return concrete GRTs without association rows`(field: String) =
        withFixture { f ->
            runBlocking {
                val access = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                val imported = f.createRequest("ImportRequest", "teamName", "Engineering")
                f.assign(access)
                f.addTo(field, access, PgGraphqlObject.of("uuidId" to ROW_ONE))
                f.addTo(field, imported, PgGraphqlObject.of("uuidId" to ROW_TWO))
                val queue =
                    f
                        .readOwner("$field { nodes { ${f.requestSelection} } }")
                        .get<ObjectBase>(field, f.field(field).type.kcls)
                assertEquals(
                    listOf(access.internalID, imported.internalID),
                    queue.get<List<ObjectBase>>("nodes", f.requestField.type.kcls).map { it.internalId() },
                )
            }
        }
}

internal const val ROW_ONE = "10000000-0000-0000-0000-000000000001"
internal const val ROW_TWO = "20000000-0000-0000-0000-000000000002"
