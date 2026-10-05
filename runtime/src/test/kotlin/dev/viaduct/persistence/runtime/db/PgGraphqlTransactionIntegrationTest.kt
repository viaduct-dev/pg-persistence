package dev.viaduct.persistence.runtime.db

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import viaduct.api.context.ExecutionContext
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import java.util.UUID
import kotlin.test.Test

/**
 * Live transaction coverage against Supabase's pg_graphql endpoint.
 *
 * Set `PG_GRAPHQL_API_KEY` (preferably a local service-role key) to enable the test. The default
 * endpoint and type are `http://127.0.0.1:54321/graphql/v1` and `Group`. Override them with
 * `PG_GRAPHQL_URL` and `PG_GRAPHQL_TRANSACTION_TYPE`. Use
 * `PG_GRAPHQL_TRANSACTION_LABEL_FIELD` when the table's writable label is not `name`, and provide
 * any other required fields as a JSON object in `PG_GRAPHQL_TRANSACTION_OBJECT`.
 */
class PgGraphqlTransactionIntegrationTest {
    @Test
    fun `combined mutations commit together and roll back together`() =
        runBlocking {
            val config = transactionConfig()
            val httpClient = HttpClient(CIO)
            val pgClient = PgGraphqlClient(httpClient, config.endpoint)
            val entity = PgGraphqlEntity(config.typeName)
            val committedIds = List(2) { UUID.randomUUID().toString() }
            val rolledBackId = UUID.randomUUID().toString()
            try {
                commitInserts(httpClient, config, committedIds)
                val visibleCommittedIds = selectIds(pgClient, entity, committedIds, config.headers)
                val rollbackErrors = commitDuplicateId(httpClient, config, rolledBackId)
                val visibleRolledBackIds = selectIds(pgClient, entity, listOf(rolledBackId), config.headers)

                assertThat(
                    TransactionOutcome(
                        visibleCommittedIds,
                        rollbackErrors.map(UpstreamGraphqlError::message).isNotEmpty(),
                        visibleRolledBackIds,
                    ),
                ).isEqualTo(TransactionOutcome(committedIds.toSet(), hasRollbackError = true, emptySet()))
            } finally {
                deleteRows(pgClient, entity, committedIds + rolledBackId, config.headers)
                httpClient.close()
            }
        }

    private suspend fun commitInserts(
        httpClient: HttpClient,
        config: TransactionConfig,
        ids: List<String>,
    ) {
        val transaction = transaction(httpClient, config)
        val entity = transactionEntity(transaction, config.typeName)
        ids.forEachIndexed { index, id ->
            entity.insert(config.insertObject(id, "commit-$index"))
        }
        transaction.commit()
    }

    private suspend fun commitDuplicateId(
        httpClient: HttpClient,
        config: TransactionConfig,
        id: String,
    ): List<UpstreamGraphqlError> {
        val transaction = transaction(httpClient, config)
        val entity = transactionEntity(transaction, config.typeName)
        entity.insert(config.insertObject(id, "rollback-first"))
        entity.insert(config.insertObject(id, "rollback-second"))
        return transaction.commitResult().errors
    }

    private fun transaction(
        httpClient: HttpClient,
        config: TransactionConfig,
    ): DbTransaction =
        DbClient(
            httpClient = httpClient,
            endpoint = config.endpoint,
            requestHeaders = DbRequestHeaders { config.headers },
        ).beginTransaction(mockk<ExecutionContext>())

    private fun transactionEntity(
        transaction: DbTransaction,
        typeName: String,
    ): DbTransactionEntity<TransactionNode> {
        val type = mockk<Type<TransactionNode>>()
        every { type.name } returns typeName
        return DbTransactionEntity(transaction, type)
    }

    private suspend fun selectIds(
        client: PgGraphqlClient,
        entity: PgGraphqlEntity,
        ids: List<String>,
        headers: Map<String, String>,
    ): Set<String> =
        client
            .select(
                entity = entity,
                selection = "uuidId",
                filter = PgGraphqlFilter.oneOf("uuidId", ids).encoded(),
                headers = headers,
            ).map {
                it.jsonObject
                    .getValue("uuidId")
                    .jsonPrimitive.content
            }.toSet()

    private suspend fun deleteRows(
        client: PgGraphqlClient,
        entity: PgGraphqlEntity,
        ids: List<String>,
        headers: Map<String, String>,
    ) {
        runCatching {
            client.delete(
                entity = entity,
                filter = PgGraphqlFilter.oneOf("uuidId", ids),
                atMost = ids.size,
                headers = headers,
            )
        }
    }

    private fun transactionConfig(): TransactionConfig {
        val apiKey =
            System.getenv("PG_GRAPHQL_API_KEY")
                ?: System.getenv("SUPABASE_SERVICE_ROLE_KEY")
                ?: System.getenv("SUPABASE_ANON_KEY")
        assumeTrue(
            !apiKey.isNullOrBlank(),
            "Set PG_GRAPHQL_API_KEY to run the live pg_graphql transaction test",
        )
        val configuredObject = System.getenv("PG_GRAPHQL_TRANSACTION_OBJECT")
        val baseObject = configuredObject?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        return TransactionConfig(
            endpoint = System.getenv("PG_GRAPHQL_URL") ?: "http://127.0.0.1:54321/graphql/v1",
            apiKey = requireNotNull(apiKey),
            typeName = System.getenv("PG_GRAPHQL_TRANSACTION_TYPE") ?: "Group",
            labelField = System.getenv("PG_GRAPHQL_TRANSACTION_LABEL_FIELD") ?: "name",
            baseObject = baseObject,
        )
    }

    private data class TransactionConfig(
        val endpoint: String,
        val apiKey: String,
        val typeName: String,
        val labelField: String,
        val baseObject: JsonObject,
    ) {
        val headers = mapOf("apikey" to apiKey, "Authorization" to "Bearer $apiKey")

        fun insertObject(
            id: String,
            label: String,
        ): PgGraphqlObject =
            PgGraphqlObject.of(
                *baseObject.map { (name, value) -> name to value }.toTypedArray(),
                "uuidId" to id,
                labelField to "pg-persistence-${UUID.randomUUID()}-$label",
            )
    }

    private data class TransactionOutcome(
        val committedIds: Set<String>,
        val hasRollbackError: Boolean,
        val rolledBackIds: Set<String>,
    )

    private class TransactionNode : NodeObject
}
