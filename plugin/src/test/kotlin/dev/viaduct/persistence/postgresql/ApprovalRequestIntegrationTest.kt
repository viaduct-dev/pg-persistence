@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.postgresql.ApprovalRequestFixture.Companion.withFixture
import dev.viaduct.persistence.runtime.db.PgGraphqlObject
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlException
import dev.viaduct.persistence.runtime.db.withReference
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.ObjectBase
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApprovalRequestIntegrationTest {
    @ParameterizedTest
    @CsvSource(
        "AccessRequest,requestedPermission,EDITOR",
        "ImportRequest,teamName,Engineering",
        "OnboardingRequest,notificationEmail,guest@example.test",
    )
    fun `stored union reads the concrete generated GRT`(
        kind: String,
        field: String,
        value: String,
    ) = withFixture { fixture ->
        runBlocking {
            val target = fixture.createRequest(kind, field, value)
            fixture.assign(target)
            val request = fixture.readRequest()
            assertEquals(kind + fixture.suffix, request.javaClass.simpleName)
            assertEquals(value, request.get<String>(field, String::class))
            assertEquals(target.internalID, request.internalId())
        }
    }

    @Test
    fun `changing reference across all three types clears the previous target`() =
        withFixture { fixture ->
            runBlocking {
                val access = fixture.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                val imported = fixture.createRequest("ImportRequest", "teamName", "Engineering")
                val onboarding = fixture.createRequest("OnboardingRequest", "notificationEmail", "guest@example.test")
                fixture.assign(access)
                val observed =
                    listOf(imported, onboarding, access).map { target ->
                        fixture.change(target)
                        fixture.readRequest().internalId()
                    }
                assertEquals(listOf(imported, onboarding, access).map { it.internalID }, observed)
            }
        }

    @Test
    fun `database rejects two populated union targets`() =
        withFixture { fixture ->
            runBlocking {
                val access = fixture.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                val imported = fixture.createRequest("ImportRequest", "teamName", "Engineering")
                val failure =
                    assertFailsWith<UpstreamGraphqlException> {
                        fixture.insertAssignment(
                            PgGraphqlObject.of(
                                "request${access.type.name}Id" to access.internalID,
                                "request${imported.type.name}Id" to imported.internalID,
                            ),
                        )
                    }
                assertTrue(failure.errors.any { it.message.contains("check constraint") }, failure.message)
            }
        }

    @Test
    fun `database rejects a missing required union target`() =
        withFixture { fixture ->
            runBlocking {
                val failure =
                    assertFailsWith<UpstreamGraphqlException> {
                        fixture.insertAssignment(PgGraphqlObject.of("uuidId" to fixture.assignmentId))
                    }
                assertTrue(failure.errors.any { it.message.contains("check constraint") }, failure.message)
            }
        }

    @Test
    fun `database rejects a reference to a nonexistent request`() =
        withFixture { fixture ->
            runBlocking {
                val missing =
                    GlobalID(
                        fixture.type("AccessRequest" + fixture.suffix),
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    )
                val failure = assertFailsWith<UpstreamGraphqlException> { fixture.assign(missing) }
                assertTrue(failure.errors.any { it.message.contains("foreign key constraint") }, failure.message)
            }
        }

    @Test
    fun `failed target change retains the previous request`() =
        withFixture { fixture ->
            runBlocking {
                val access = fixture.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                fixture.assign(access)
                val missing =
                    GlobalID(
                        fixture.type("ImportRequest" + fixture.suffix),
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    )
                assertFailsWith<UpstreamGraphqlException> { fixture.change(missing) }
                assertEquals(access.internalID, fixture.readRequest().internalId())
            }
        }

    @Test
    fun `reference helper rejects objects outside the union`() =
        withFixture { fixture ->
            runBlocking {
                val unrelated = GlobalID(fixture.type(fixture.assignment.typeName), fixture.assignmentId)
                assertFailsWith<IllegalArgumentException> {
                    PgGraphqlObject.of().withReference(fixture.requestField, unrelated)
                }
            }
        }
}

internal fun ObjectBase.internalId(): String = get<GlobalID<*>>("id", GlobalID::class).internalID
