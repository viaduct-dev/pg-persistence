package dev.viaduct.persistence.runtime
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.util.reflect.typeInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Live contract coverage for the connection shape emitted by pg_graphql.
 *
 * Set PG_GRAPHQL_API_KEY (and optionally PG_GRAPHQL_URL and PG_GRAPHQL_COLLECTION_FIELD) to run
 * this test against a local Supabase instance or another pg_graphql endpoint. The test is skipped
 * when no API key is configured so the normal library test suite remains hermetic.
 */
class PgGraphqlIntegrationTest {
    @Test
    fun `fetches a pg_graphql connection with edges cursors and page info`() =
        runBlocking {
            val config = testConfig()
            val httpClient = HttpClient(CIO)
            try {
                assertConnectionContract(httpClient, config)
            } finally {
                httpClient.close()
            }
        }

    @Test
    fun `reads plain many-to-many association edges from real pg_graphql tables`() =
        runBlocking {
            val config = associationConfig()
            val httpClient = HttpClient(CIO)
            try {
                assertAssociationContract(httpClient, config)
            } finally {
                httpClient.close()
            }
        }

    private fun testConfig(): PgGraphqlTestConfig {
        val apiKey =
            System.getenv("PG_GRAPHQL_API_KEY")
                ?: System.getenv("SUPABASE_ANON_KEY")
        assumeTrue(
            !apiKey.isNullOrBlank(),
            "Set PG_GRAPHQL_API_KEY to run the live pg_graphql integration test",
        )
        return PgGraphqlTestConfig(
            endpoint = System.getenv("PG_GRAPHQL_URL") ?: "http://127.0.0.1:54321/graphql/v1",
            apiKey = requireNotNull(apiKey),
            collectionField = System.getenv("PG_GRAPHQL_COLLECTION_FIELD") ?: "groupCollection",
        )
    }

    private fun associationConfig(): PgGraphqlAssociationTestConfig {
        val apiKey =
            System.getenv("PG_GRAPHQL_API_KEY")
                ?: System.getenv("SUPABASE_ANON_KEY")
        assumeTrue(
            !apiKey.isNullOrBlank(),
            "Set PG_GRAPHQL_API_KEY to run the live pg_graphql integration test",
        )
        val associationField = System.getenv("PG_GRAPHQL_ASSOCIATION_FIELD")
        assumeTrue(
            !associationField.isNullOrBlank(),
            "Set PG_GRAPHQL_ASSOCIATION_FIELD to run the association pg_graphql integration test",
        )
        return PgGraphqlAssociationTestConfig(
            endpoint = System.getenv("PG_GRAPHQL_URL") ?: "http://127.0.0.1:54321/graphql/v1",
            apiKey = requireNotNull(apiKey),
            ownerCollectionField =
                System.getenv("PG_GRAPHQL_ASSOCIATION_OWNER_COLLECTION_FIELD") ?: "groupCollection",
            associationField = requireNotNull(associationField),
        )
    }

    private suspend fun assertConnectionContract(
        httpClient: HttpClient,
        config: PgGraphqlTestConfig,
    ) {
        val response =
            httpClient.post(config.endpoint) {
                header("apikey", config.apiKey)
                setBody(
                    TextContent(
                        text =
                            buildJsonObject {
                                put("query", connectionQuery(config.collectionField))
                                put("variables", buildJsonObject {})
                            }.toString(),
                        contentType = ContentType.Application.Json,
                    ),
                    typeInfo<TextContent>(),
                )
            }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNull(body["errors"], body.toString())
        val connection =
            requireNotNull(body["data"]?.jsonObject?.get(config.collectionField)?.jsonObject) {
                body.toString()
            }
        assertNotNull(connection["edges"], body.toString())
        assertNotNull(connection["pageInfo"]?.jsonObject, body.toString())
    }

    private suspend fun assertAssociationContract(
        httpClient: HttpClient,
        config: PgGraphqlAssociationTestConfig,
    ) {
        val response =
            httpClient.post(config.endpoint) {
                header("apikey", config.apiKey)
                setBody(
                    TextContent(
                        text =
                            buildJsonObject {
                                put("query", associationQuery(config))
                                put("variables", buildJsonObject {})
                            }.toString(),
                        contentType = ContentType.Application.Json,
                    ),
                    typeInfo<TextContent>(),
                )
            }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNull(body["errors"], body.toString())
        val ownerConnection =
            requireNotNull(body["data"]?.jsonObject?.get(config.ownerCollectionField)?.jsonObject) {
                body.toString()
            }
        val owner =
            ownerConnection["edges"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("node")
                ?.jsonObject
        if (owner == null) return
        val association =
            requireNotNull(owner[config.associationField]?.jsonObject) {
                body.toString()
            }
        assertNotNull(association["edges"], body.toString())
        assertNotNull(association["pageInfo"]?.jsonObject, body.toString())
        association["edges"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("node")
            ?.jsonObject
            ?.let { row -> assertNotNull(row["node"]?.jsonObject, body.toString()) }
    }

    private fun connectionQuery(collectionField: String): String =
        """
        query PgGraphqlConnectionContract {
          $collectionField(first: 1) {
            edges {
              cursor
              node { uuidId }
            }
            pageInfo {
              hasNextPage
              hasPreviousPage
              startCursor
              endCursor
            }
          }
        }
        """.trimIndent()

    private fun associationQuery(config: PgGraphqlAssociationTestConfig): String =
        """
        query PgGraphqlAssociationContract {
          ${config.ownerCollectionField}(first: 1) {
            edges {
              node {
                uuidId
                ${config.associationField}(first: 1) {
                  edges {
                    cursor
                    node {
                      node { uuidId }
                    }
                  }
                  pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                }
              }
            }
          }
        }
        """.trimIndent()

    private data class PgGraphqlTestConfig(
        val endpoint: String,
        val apiKey: String,
        val collectionField: String,
    )

    private data class PgGraphqlAssociationTestConfig(
        val endpoint: String,
        val apiKey: String,
        val ownerCollectionField: String,
        val associationField: String,
    )
}
