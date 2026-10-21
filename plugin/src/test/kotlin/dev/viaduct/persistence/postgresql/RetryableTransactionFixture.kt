@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.gradle.PersistencePlacementRegistrar
import dev.viaduct.persistence.gradle.PersistenceSchemaModelLoader
import dev.viaduct.persistence.hibernate.EffectiveHibernateModelBuilder
import dev.viaduct.persistence.hibernate.HibernateMetadataBootstrap
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationFactory
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationInput
import dev.viaduct.persistence.hibernate.HibernateSchemaModelWriter
import dev.viaduct.persistence.jdbc.JdbcOperations
import dev.viaduct.persistence.pggraphql.overlay.PgGraphqlOverlay
import dev.viaduct.persistence.pggraphql.overlay.RetryableTransactionOverlay
import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.DbRetryableTransactions
import dev.viaduct.persistence.runtime.db.DbTransactionIdentity
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.executionContext
import viaduct.engine.api.mocks.createSchemaWithWiring
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/** Uses a dedicated database in the running Supabase PostgreSQL container. No application tables are touched. */
internal class RetryableTransactionFixture(
    private val splitSchemas: Boolean = false,
) : Closeable {
    private val url = System.getenv("PG_RETRY_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:55322/kan22_retry_tests"
    private val directory = Files.createTempDirectory("retryable-transactions-").toFile()
    private val password = System.getenv("PG_RETRY_PASSWORD") ?: "postgres"
    private val clients = mutableListOf<HttpClient>()
    private val suffix =
        java.util.UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(8)
    private val people = "kan37_retry_people_$suffix"
    private val orders = "kan37_retry_orders_$suffix"
    private val factory: org.hibernate.SessionFactory
    private val handle: dev.viaduct.persistence.hibernate.HibernateMetadataHandle
    val context =
        MockInternalContext.create(createSchemaWithWiring("extend type Query { hello: String }")).executionContext

    init {
        require(url.matches(Regex("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/kan22_retry_tests"))) {
            "Retry integration tests require an isolated local kan22_retry_tests database"
        }
        val schema = directory.resolve("schema").apply { check(mkdirs()) }
        schema.resolve("Model.graphqls").writeText(
            "interface Node { id: ID! } type RetryMember implements Node { id: ID!, name: String! }" +
                if (splitSchemas) {
                    " type RetryPurchase implements Node { id: ID!, label: String!, buyer: RetryMember! }"
                } else {
                    ""
                },
        )
        val policy = directory.resolve("pg-persistence.yaml").apply { writeText("retryableTransactions: true\n") }
        val placements =
            if (splitSchemas) {
                listOf("RetryMember" to people, "RetryPurchase" to orders).map { (type, placement) ->
                    directory
                        .resolve(placement)
                        .apply { check(mkdirs()) }
                        .resolve(PersistencePlacementRegistrar.FILE_NAME)
                        .apply {
                            writeText(
                                buildJsonObject {
                                    put("version", 1)
                                    put("module", "fixture.$placement")
                                    put("databaseSchema", placement)
                                    put("types", JsonArray(listOf(JsonPrimitive(type))))
                                }.toString(),
                            )
                        }
                }
            } else {
                emptyList()
            }
        val model = PersistenceSchemaModelLoader.build(schema, policy, modulePlacementArtifacts = placements)
        val generated = directory.resolve("generated")
        HibernateSchemaModelWriter().write(model, generated)
        val configuration =
            HibernateMetadataConfigurationFactory.create(
                HibernateMetadataConfigurationInput(
                    mappingFile = generated.resolve("resources/META-INF/viaduct-persistence.hbm.xml"),
                    classpath = System.getProperty("java.class.path").split(File.pathSeparator).map(::File),
                    semanticModel = model,
                    hibernateSettings =
                        mapOf(
                            "hibernate.connection.url" to url,
                            "hibernate.connection.username" to "postgres",
                            "hibernate.connection.password" to password,
                            "hibernate.boot.allow_jdbc_metadata_access" to "true",
                        ),
                ),
            )
        handle = HibernateMetadataBootstrap.build(configuration)
        factory = handle.metadata.buildSessionFactory()
        connection().use { database ->
            JdbcOperations.execute(database, RetryableTransactionOverlay.prerequisites(handle.metadata))
            if (splitSchemas) JdbcOperations.execute(database, "CREATE SCHEMA $people; CREATE SCHEMA $orders")
            factory.schemaManager.exportMappedObjects(false)
            val effective = EffectiveHibernateModelBuilder.build(handle.metadata, model)
            JdbcOperations.execute(database, PostgresqlOverlay.renderMigration(effective))
            JdbcOperations.execute(database, PgGraphqlOverlay.render(effective))
            JdbcOperations.execute(database, RetryableTransactionOverlay.render(handle.metadata))
        }
    }

    fun connection(): Connection =
        DriverManager.getConnection(url, "postgres", password).also { database ->
            if (splitSchemas) {
                JdbcOperations.execute(database, "SELECT set_config('search_path', ?, false)", "$people,$orders,public")
            }
        }

    fun client(
        scope: String = "tenant-1:user-1",
        attempts: Int = 5,
        afterResponse: (JsonObject) -> Unit = {},
    ): DbClient {
        val http =
            HttpClient(
                MockEngine { request ->
                    val body =
                        Json
                            .parseToJsonElement(
                                (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
                            ).jsonObject
                    val result =
                        connection().use { database ->
                            val sql = "select set_config('request.headers', ?, false)"
                            JdbcOperations.execute(
                                database,
                                sql,
                                buildJsonObject {
                                    put("x-pg-persistence-scope", request.headers["x-pg-persistence-scope"])
                                }.toString(),
                            )
                            resolve(
                                database,
                                body.getValue("query").jsonPrimitive.content,
                                body.getValue("variables").jsonObject,
                            )
                        }
                    afterResponse(result)
                    respond(result.toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )
        clients.add(http)
        return DbClient(
            http,
            "http://fixture/graphql",
            retryableTransactions =
                DbRetryableTransactions(
                    DbTransactionIdentity { scope },
                    maxAttempts = attempts,
                    initialDelayMillis = 10,
                ),
        )
    }

    fun resolve(
        database: Connection,
        document: String,
        variables: JsonObject = JsonObject(emptyMap()),
    ): JsonObject =
        JdbcOperations.query(
            database,
            "select graphql.resolve(?, ?::jsonb)",
            { rows ->
                check(rows.next())
                Json.parseToJsonElement(rows.getString(1)).jsonObject
            },
            document,
            variables.toString(),
        )

    fun countMembers(): Int = count("${if (splitSchemas) people else "public"}.retry_members")

    fun countPurchases(): Int = count("$orders.retry_purchases")

    fun countRecords(): Int = count("persistence_private.transaction_records")

    private fun count(table: String): Int =
        connection().use { database ->
            JdbcOperations.query(
                database,
                "select count(*) from $table",
                { rows ->
                    check(rows.next())
                    rows.getInt(1)
                },
            )
        }

    override fun close() {
        clients.forEach(HttpClient::close)
        factory.schemaManager.dropMappedObjects(false)
        if (splitSchemas) {
            connection().use { JdbcOperations.execute(it, "DROP SCHEMA $orders CASCADE; DROP SCHEMA $people CASCADE") }
        }
        factory.close()
        handle.close()
        directory.deleteRecursively()
    }
}
