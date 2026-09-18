@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.graphql.PgGraphqlExecutor
import graphql.language.Field
import graphql.language.OperationDefinition
import graphql.parser.Parser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.types.CompositeOutput
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ConcreteMutationPayloadTest {
    @Test
    fun `singular update rejects multirow limits before writing`() =
        runBlocking {
            var writes = 0
            val client =
                DbClient(
                    PgGraphqlExecutor { _, _ ->
                        writes++
                        error("Unexpected database write")
                    },
                )

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    client.entity<MutationRecord>().update(
                        RecordMutationContext(mockk()),
                        PgGraphqlUpdate(
                            PgGraphqlObject.of("name" to "Updated"),
                            PgGraphqlFilter.empty(),
                            atMost = 2,
                        ),
                    )
                }

            assertContains(requireNotNull(failure.message), "atMost = 1")
            assertEquals(0, writes)
        }

    @ParameterizedTest
    @EnumSource(MutationOperation::class, names = ["INSERT", "UPDATE", "INSERT_BATCH", "UPDATE_BATCH"])
    fun `missing entity field fails before writing`(operation: MutationOperation) =
        runBlocking {
            withoutWrites { client ->
                val error =
                    assertFailsWith<IllegalArgumentException> {
                        operation.execute(client, MissingRecordMutationContext(mockk()))
                    }
                assertContains(requireNotNull(error.message), "has no MutationRecord field")
            }
        }

    @ParameterizedTest
    @EnumSource(MutationOperation::class)
    fun `ambiguous concrete fields fail before writing`(operation: MutationOperation) =
        runBlocking {
            withoutWrites { client ->
                val error =
                    assertFailsWith<IllegalArgumentException> {
                        operation.execute(client, AmbiguousRecordMutationContext(mockk()))
                    }
                assertContains(requireNotNull(error.message), "multiple")
            }
        }

    @ParameterizedTest
    @EnumSource(MutationOperation::class)
    fun `wrong payload cardinality fails before writing`(operation: MutationOperation) =
        runBlocking {
            withoutWrites { client ->
                val error =
                    assertFailsWith<IllegalArgumentException> {
                        if (operation.batch) {
                            operation.execute(client, RecordMutationContext(mockk()))
                        } else {
                            operation.execute(client, RecordsMutationContext(mockk()))
                        }
                    }
                assertContains(requireNotNull(error.message), if (operation.batch) "list-valued" else "singular")
            }
        }

    @ParameterizedTest
    @EnumSource(MutationOperation::class)
    fun `mutations request IDs and build the declared concrete payload`(operation: MutationOperation) =
        runBlocking {
            HttpClient(
                MockEngine { request ->
                    val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    val json = Json.parseToJsonElement(body)
                    val query =
                        Parser().parseDocument(
                            json.jsonObject
                                .getValue("query")
                                .jsonPrimitive.content,
                        )
                    val root =
                        query
                            .getDefinitionsOfType(OperationDefinition::class.java)
                            .single()
                            .selectionSet.selections
                            .single() as Field
                    val records =
                        requireNotNull(root.selectionSet)
                            .selections
                            .filterIsInstance<Field>()
                            .single { it.name == "records" }
                    assertEquals(
                        listOf("uuidId"),
                        requireNotNull(records.selectionSet)
                            .selections
                            .filterIsInstance<Field>()
                            .map { it.name },
                    )
                    respond(
                        """{"data":{"${root.name}":{"affectedCount":1,"records":[{"uuidId":"record-1"}]}}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ).use { http ->
                val client = client(http)
                if (operation.batch) {
                    val ctx = RecordsMutationContext(mockk())
                    val record = reference(ctx)
                    assertEquals(listOf(record), operation.execute(client, ctx).records)
                } else {
                    val ctx = RecordMutationContext(mockk())
                    val record = reference(ctx)
                    assertSame(record, operation.execute(client, ctx).record)
                }
            }
        }

    @ParameterizedTest
    @EnumSource(MutationOperation::class, names = ["DELETE", "DELETE_BATCH"])
    fun `delete permits a payload without an entity field`(operation: MutationOperation) =
        runBlocking {
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond(
                        """{"data":{"deleteFromMutationRecordCollection":{"affectedCount":1,"records":[]}}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ).use { http ->
                operation.execute(client(http), MissingRecordMutationContext(mockk()))
                assertEquals(1, requests)
            }
        }

    private suspend fun withoutWrites(block: suspend (DbClient) -> Unit) {
        HttpClient(MockEngine { error("Unexpected database write") }).use { http -> block(client(http)) }
    }

    private fun client(http: HttpClient) =
        DbClient(
            http,
            "https://example.test/graphql/v1",
            DbRequestHeaders { emptyMap() },
        )

    private fun reference(ctx: MutationFieldExecutionContext<*, *, *, *>): MutationRecord {
        val id = mockk<GlobalID<MutationRecord>>()
        val record = MutationRecord()
        every { ctx.globalIDFor(MutationRecord.Reflection, "record-1") } returns id
        every { ctx.ref(id) } returns record
        return record
    }
}

enum class MutationOperation(
    val batch: Boolean,
) {
    INSERT(false),
    UPDATE(false),
    DELETE(false),
    INSERT_BATCH(true),
    UPDATE_BATCH(true),
    DELETE_BATCH(true),
    ;

    suspend fun <P : CompositeOutput> execute(
        client: DbClient,
        ctx: MutationFieldExecutionContext<*, *, *, P>,
    ): P {
        val entity = client.entity<MutationRecord>()
        val input = PgGraphqlObject.of("name" to "Record")
        val update = PgGraphqlUpdate(input, PgGraphqlFilter.eq("uuidId", "record-1"))
        val delete = PgGraphqlDelete(PgGraphqlFilter.eq("uuidId", "record-1"))
        return when (this) {
            INSERT -> entity.insert(ctx, input)
            UPDATE -> entity.update(ctx, update)
            DELETE -> entity.delete(ctx, delete)
            INSERT_BATCH -> entity.insertBatch(ctx, listOf(input))
            UPDATE_BATCH -> entity.updateBatch(ctx, listOf(update))
            DELETE_BATCH -> entity.deleteBatch(ctx, listOf(delete))
        }
    }
}
