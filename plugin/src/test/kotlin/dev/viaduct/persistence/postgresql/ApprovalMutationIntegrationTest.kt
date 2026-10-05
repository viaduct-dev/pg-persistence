@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.postgresql.ApprovalRequestFixture.Companion.withFixture
import dev.viaduct.persistence.runtime.db.PgGraphqlDelete
import dev.viaduct.persistence.runtime.db.PgGraphqlEntity
import dev.viaduct.persistence.runtime.db.PgGraphqlFilter
import dev.viaduct.persistence.runtime.db.PgGraphqlObject
import dev.viaduct.persistence.runtime.db.PgGraphqlUpdate
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.api.internal.ObjectBase
import viaduct.api.types.CompositeOutput
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApprovalMutationIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = ["SavePayload", "InterfacePayload", "SaveResult"])
    fun `insert returns a generated payload with the ID actually persisted`(payload: String) =
        withFixture { f ->
            runBlocking {
                val id = UUID.randomUUID().toString()
                val result =
                    f.entity("AccessRequest").insert(
                        f.mutationContext(payload),
                        f.requestValues(id, "requestedPermission", "EDITOR"),
                    ) as ObjectBase
                val target = result.get<ObjectBase>("request", f.requestField.type.kcls)
                assertEquals(id, target.internalId())
                assertEquals("AccessRequest${f.suffix}", target.javaClass.simpleName)
                assertEquals(setOf(id), f.ids(PgGraphqlEntity("AccessRequest${f.suffix}")))
                val read =
                    f.readRoot(
                        viaduct.api.globalid.GlobalID(f.type("AccessRequest${f.suffix}"), id),
                        "ApprovalRequest",
                        "... on AccessRequest${f.suffix} { requestedPermission }",
                    )
                assertEquals("EDITOR", read.get<String>("requestedPermission", String::class))
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["BatchPayload", "BatchInterfacePayload", "BatchResult"])
    fun `batch insert returns exactly the persisted IDs`(payload: String) =
        withFixture { f ->
            runBlocking {
                val ids = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
                val inserted =
                    f.entity("AccessRequest").insertBatch(
                        f.mutationContext(payload),
                        ids.map { f.requestValues(it, "requestedPermission", "VIEWER") },
                    )
                assertEquals(ids, f.resultIds(inserted))
                assertEquals(ids.toSet(), f.ids(PgGraphqlEntity("AccessRequest${f.suffix}")))
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["BatchPayload", "BatchInterfacePayload", "BatchResult"])
    fun `batch update returns IDs of rows with the new values`(payload: String) =
        withFixture { f ->
            runBlocking {
                val targets = List(2) { f.createRequest("AccessRequest", "requestedPermission", "VIEWER") }
                val updated =
                    f.entity("AccessRequest").updateBatch(
                        f.mutationContext(payload),
                        targets.map {
                            PgGraphqlUpdate(
                                PgGraphqlObject.of("requestedPermission" to "EDITOR"),
                                PgGraphqlFilter.eq("uuidId", it.internalID),
                            )
                        },
                    )
                assertEquals(targets.map { it.internalID }, f.resultIds(updated))
                val values =
                    targets.map {
                        f
                            .readRoot(it, "ApprovalRequest", "... on AccessRequest${f.suffix} { requestedPermission }")
                            .get<String>("requestedPermission", String::class)
                    }
                assertEquals(listOf("EDITOR", "EDITOR"), values)
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["BatchPayload", "BatchInterfacePayload", "BatchResult"])
    fun `batch delete returns deleted IDs and leaves no rows`(payload: String) =
        withFixture { f ->
            runBlocking {
                val targets = List(2) { f.createRequest("AccessRequest", "requestedPermission", "VIEWER") }
                val deleted =
                    f.entity("AccessRequest").deleteBatch(
                        f.mutationContext(payload),
                        targets.map { PgGraphqlDelete(PgGraphqlFilter.eq("uuidId", it.internalID)) },
                    )
                assertEquals(targets.map { it.internalID }, f.resultIds(deleted))
                assertEquals(emptySet(), f.ids(PgGraphqlEntity("AccessRequest${f.suffix}")))
            }
        }

    @Test
    fun `ambiguous payload fails before writing and explicit payload resolves it`() =
        withFixture { f ->
            runBlocking {
                val entity = f.entity("AccessRequest")
                val ctx = f.mutationContext("AmbiguousResult")
                val id = UUID.randomUUID().toString()
                val values = f.requestValues(id, "requestedPermission", "EDITOR")
                val failure = assertFailsWith<IllegalArgumentException> { entity.insert(ctx, values) }
                kotlin.test.assertContains(failure.message.orEmpty(), "payloadType")
                assertEquals(emptySet(), f.ids(PgGraphqlEntity("AccessRequest${f.suffix}")))
                val payloadType = f.reflection("SaveAlternative${f.suffix}")
                val payload = entity.insert(ctx, values, payloadType = payloadType) as ObjectBase
                assertEquals(id, payload.get<ObjectBase>("request", f.requestField.type.kcls).internalId())
            }
        }

    private fun ApprovalRequestFixture.resultIds(result: CompositeOutput): List<String> =
        (result as ObjectBase).get<List<ObjectBase>>("requests", requestField.type.kcls).map { it.internalId() }

    @Test
    fun `failed batch insert does not leave earlier rows behind`() =
        withFixture { f ->
            runBlocking {
                val id = UUID.randomUUID().toString()
                val value = f.requestValues(id, "requestedPermission", "VIEWER")
                assertFailsWith<UpstreamGraphqlException> {
                    f.entity("AccessRequest").insertBatch(f.mutationContext("BatchPayload"), listOf(value, value))
                }
                assertEquals(emptySet(), f.ids(PgGraphqlEntity("AccessRequest${f.suffix}")))
            }
        }
}
