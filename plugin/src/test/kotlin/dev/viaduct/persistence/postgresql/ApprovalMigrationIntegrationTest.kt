@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.postgresql.ApprovalMigrationFixture.Companion.withMigrationDatabase
import dev.viaduct.persistence.runtime.db.PgGraphqlObject
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlException
import org.junit.jupiter.api.Test
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.ObjectBase
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApprovalMigrationIntegrationTest {
    @Test
    fun `adding a possible type migrates columns foreign keys constraints and read metadata`() =
        withMigrationDatabase { db ->
            val expanded = ApprovalRequestFixture.schema(db.suffix)
            val original = withoutOnboarding(expanded, db.suffix)
            db.version(original) { old, migrate ->
                migrate()
                val existing = old.createRequest("ImportRequest", "teamName", "Engineering")
                old.assign(existing)
                db.version(expanded) { next, upgrade ->
                    upgrade()
                    val retained =
                        next
                            .readOwner("request { ${next.requestSelection} }", old.assignmentId)
                            .get<ObjectBase>("request", next.requestField.type.kcls)
                    assertEquals(existing.internalID, retained.internalId())
                    val added = next.createRequest("OnboardingRequest", "notificationEmail", "guest@example.com")
                    next.assign(added)
                    assertEquals(added.internalID, next.readRequest().internalId())
                    next.change(added, "reviewable")
                    val throughInterface =
                        next
                            .readOwner("reviewable { summary }")
                            .get<ObjectBase>("reviewable", next.field("reviewable").type.kcls)
                    assertEquals("Review guest@example.com", throughInterface.get<String>("summary", String::class))
                    next.addTo("requests", added)
                    val list =
                        next
                            .readOwner("requests { ${next.requestSelection} }")
                            .get<List<ObjectBase>>("requests", next.requestField.type.kcls)
                    assertEquals(listOf(added.internalID), list.map { it.internalId() })
                    // The new column must have a real FK, not just a generated field name.
                    val missingTarget =
                        assertFailsWith<UpstreamGraphqlException> {
                            next.change(GlobalID(added.type, UUID.randomUUID().toString()))
                        }
                    assertContains(missingTarget.message.orEmpty(), "foreign key constraint")
                    assertEquals(added.internalID, next.readRequest().internalId())
                    // Both old and new targets populated must still violate the exclusive-target rule.
                    val bothTargets =
                        assertFailsWith<UpstreamGraphqlException> {
                            next.insertAssignment(
                                PgGraphqlObject.of(
                                    "request${existing.type.name}Id" to existing.internalID,
                                    "request${added.type.name}Id" to added.internalID,
                                ),
                            )
                        }
                    assertContains(bothTargets.message.orEmpty(), "check constraint")
                }
            }
        }

    @Test
    fun `removing a used target fails until references have been migrated`() =
        withMigrationDatabase { db ->
            val expanded = ApprovalRequestFixture.schema(db.suffix)
            db.version(expanded) { old, migrate ->
                migrate()
                val removed = old.createRequest("OnboardingRequest", "notificationEmail", "guest@example.com")
                val retained = old.createRequest("ImportRequest", "teamName", "Engineering")
                old.assign(removed)
                db.version(withoutOnboarding(expanded, db.suffix), includeDestructiveReview = true) { next, downgrade ->
                    val failure = assertFailsWith<SQLException> { downgrade() }
                    assertEquals("23514", failure.sqlState)
                    assertEquals(removed.internalID, old.readRequest().internalId())
                    old.change(retained)
                    downgrade()
                    val target =
                        next
                            .readOwner("request { ... on ImportRequest${db.suffix} { id teamName } }", old.assignmentId)
                            .get<ObjectBase>("request", next.requestField.type.kcls)
                    assertEquals(retained.internalID, target.internalId())
                    assertEquals("Engineering", target.get<String>("teamName", String::class))
                }
            }
        }

    private fun withoutOnboarding(
        sdl: String,
        suffix: String,
    ): String =
        sdl
            .replace(" | OnboardingRequest$suffix", "")
            .replace(
                "type OnboardingRequest$suffix implements Node & Summarized$suffix & Reviewable$suffix",
                "type OnboardingRequest$suffix implements Node",
            )
}
