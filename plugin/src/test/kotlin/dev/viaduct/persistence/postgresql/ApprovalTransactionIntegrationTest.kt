@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.postgresql.ApprovalRequestFixture.Companion.withFixture
import dev.viaduct.persistence.runtime.db.PgGraphqlDelete
import dev.viaduct.persistence.runtime.db.PgGraphqlEntity
import dev.viaduct.persistence.runtime.db.PgGraphqlFilter
import dev.viaduct.persistence.runtime.db.PgGraphqlObject
import dev.viaduct.persistence.runtime.db.PgGraphqlUpdate
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlException
import dev.viaduct.persistence.runtime.db.pgGraphqlAssociation
import dev.viaduct.persistence.runtime.db.withReference
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.ObjectBase
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApprovalTransactionIntegrationTest {
    @Test
    fun `one transaction creates a request its owner and its mixed collection entry`() =
        withFixture { f ->
            runBlocking {
                val request = GlobalID(f.type("AccessRequest${f.suffix}"), UUID.randomUUID().toString())
                val owner = GlobalID(f.type(f.assignment.typeName), f.assignmentId)
                val association = f.field("requests").pgGraphqlAssociation()
                f.transaction {
                    insert(
                        PgGraphqlEntity(request.type.name),
                        f.requestValues(request.internalID, "requestedPermission", "EDITOR"),
                    )
                    insert(
                        f.assignment,
                        PgGraphqlObject.of("uuidId" to owner.internalID).withReference(f.requestField, request),
                    )
                    insert(association.entity, association.insertObject(owner, request))
                }
                assertEquals(request.internalID, f.readRequest().internalId())
                assertEquals(listOf(request.internalID), f.requestIds())
            }
        }

    @Test
    fun `association update and delete commit together`() =
        withFixture { f ->
            runBlocking {
                val first = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                val second = f.createRequest("ImportRequest", "teamName", "Engineering")
                f.assign(first)
                f.addTo("requests", first, PgGraphqlObject.of("uuidId" to ROW_ONE))
                f.addTo("requests", first, PgGraphqlObject.of("uuidId" to ROW_TWO))
                val association = f.field("requests").pgGraphqlAssociation()
                f.transaction {
                    update(
                        association.entity,
                        PgGraphqlUpdate(
                            association.withTarget(PgGraphqlObject.of(), second),
                            PgGraphqlFilter.eq("uuidId", ROW_ONE),
                        ),
                    )
                    delete(association.entity, PgGraphqlDelete(PgGraphqlFilter.eq("uuidId", ROW_TWO)))
                }
                assertEquals(listOf(second.internalID), f.requestIds())
                assertEquals(setOf(first.internalID), f.ids(PgGraphqlEntity(first.type.name)))
            }
        }

    @Test
    fun `later foreign key failure rolls back earlier inserts updates and deletes`() =
        withFixture { f ->
            runBlocking {
                val first = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                val second = f.createRequest("ImportRequest", "teamName", "Engineering")
                f.assign(first)
                f.addTo("requests", first, PgGraphqlObject.of("uuidId" to ROW_ONE))
                f.addTo("requests", second, PgGraphqlObject.of("uuidId" to ROW_TWO))
                val insertedId = UUID.randomUUID().toString()
                val missing = GlobalID(second.type, UUID.randomUUID().toString())
                val owner = GlobalID(f.type(f.assignment.typeName), f.assignmentId)
                val association = f.field("requests").pgGraphqlAssociation()
                assertFailsWith<UpstreamGraphqlException> {
                    f.transaction {
                        insert(
                            PgGraphqlEntity(first.type.name),
                            f.requestValues(insertedId, "requestedPermission", "VIEWER"),
                        )
                        update(
                            association.entity,
                            PgGraphqlUpdate(
                                association.withTarget(PgGraphqlObject.of(), second),
                                PgGraphqlFilter.eq("uuidId", ROW_ONE),
                            ),
                        )
                        delete(association.entity, PgGraphqlDelete(PgGraphqlFilter.eq("uuidId", ROW_TWO)))
                        insert(association.entity, association.insertObject(owner, missing))
                    }
                }
                assertEquals(listOf(first.internalID, second.internalID), f.requestIds())
                assertEquals(setOf(first.internalID), f.ids(PgGraphqlEntity(first.type.name)))
            }
        }

    @Test
    fun `aborting buffered union writes leaves the database unchanged`() =
        withFixture { f ->
            runBlocking {
                val request = f.createRequest("AccessRequest", "requestedPermission", "EDITOR")
                val transaction = f.beginTransaction()
                transaction.insert(
                    f.assignment,
                    PgGraphqlObject.of("uuidId" to f.assignmentId).withReference(f.requestField, request),
                )
                transaction.abort()
                assertEquals(emptySet(), f.ids(f.assignment))
                assertEquals(setOf(request.internalID), f.ids(PgGraphqlEntity(request.type.name)))
            }
        }
}

private suspend fun ApprovalRequestFixture.requestIds(): List<String> =
    readOwner("requests { $requestSelection }")
        .get<List<ObjectBase>>("requests", requestField.type.kcls)
        .map { it.internalId() }
