package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.gradle.PersistenceSchemaModelLoader
import dev.viaduct.persistence.hibernate.EffectiveHibernateModelBuilder
import dev.viaduct.persistence.hibernate.HibernateMetadataBootstrap
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationFactory
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationInput
import dev.viaduct.persistence.hibernate.HibernateSchemaModelWriter
import dev.viaduct.persistence.jdbc.JdbcOperations
import dev.viaduct.persistence.pggraphql.overlay.PgGraphqlOverlay
import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslation
import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslationSchema
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.Type
import graphql.language.TypeName
import graphql.parser.Parser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Acceptance coverage from authored GraphQL through Hibernate, pg_graphql, and translation. */
class GraphqlToPgGraphqlIntegrationTest {
    @Test
    fun `graphql alone produces matching dynamic Hibernate and translated pg_graphql`() {
        val connectionDetails = connectionDetailsOrSkip()
        val suffix =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(10)
        val scenario = scenario(suffix)
        val directory =
            java.nio.file.Files
                .createTempDirectory("graphql-pggraphql-acceptance")
                .toFile()
        try {
            val schemaDirectory = directory.resolve("schema").apply { check(mkdirs()) }
            schemaDirectory.resolve("Model.graphqls").writeText(scenario.graphql)
            val semanticModel = PersistenceSchemaModelLoader.build(schemaDirectory, null)
            val generated = directory.resolve("generated")
            HibernateSchemaModelWriter().write(semanticModel, generated)
            val mapping = generated.resolve("resources/META-INF/viaduct-persistence.hbm.xml")
            val configuration = configuration(mapping, semanticModel, connectionDetails)

            HibernateMetadataBootstrap.build(configuration).use { handle ->
                verifyPipeline(handle, semanticModel, scenario, connectionDetails)
            }
            assertFalse(generated.resolve("kotlin").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun verifyPipeline(
        handle: dev.viaduct.persistence.hibernate.HibernateMetadataHandle,
        semanticModel: dev.viaduct.persistence.model.PersistenceModel,
        scenario: Scenario,
        database: DatabaseConnectionDetails,
    ) {
        assertEquals(
            scenario.entityNames,
            handle.metadata.entityBindings
                .map { it.entityName }
                .toSet(),
        )
        assertTrueDynamic(handle.metadata.entityBindings.map { it.className })
        val effectiveModel = EffectiveHibernateModelBuilder.build(handle.metadata, semanticModel)
        val tables = effectiveModel.entities.associateBy { it.graphqlName }
        val personTable = requireNotNull(tables[scenario.personType])
        val teamTable = requireNotNull(tables[scenario.teamType])
        assertEquals("_uuid_id", personTable.internalIdColumnName)
        assertEquals("_uuid_id", teamTable.internalIdColumnName)

        handle.metadata.buildSessionFactory().use { sessionFactory ->
            val schemaManager = sessionFactory.schemaManager
            try {
                schemaManager.exportMappedObjects(false)
                database.connect().use { connection ->
                    connection.verifyDatabase(effectiveModel, scenario, personTable.tableName, teamTable.tableName)
                }
            } finally {
                schemaManager.dropMappedObjects(false)
            }
        }
    }

    private fun Connection.verifyDatabase(
        model: dev.viaduct.persistence.hibernate.EffectiveHibernateModel,
        scenario: Scenario,
        personTable: String,
        teamTable: String,
    ) {
        execute(PostgresqlOverlay.renderMigration(model))
        execute(PgGraphqlOverlay.render(model))
        val personId = UUID.randomUUID()
        insertFixture(personTable, personId, "Ada")
        insertTeam(teamTable, UUID.randomUUID(), personId)
        val translatedFragment =
            PgGraphqlTranslation.translateSelectionDocument(
                "fragment Main on ${scenario.collectionType} { nodes { nickname owner { displayName } } }",
                translationSchema(scenario.graphql),
            )
        val query =
            PgGraphqlTranslation.buildRootQuery(
                field = scenario.rootField,
                arguments = "",
                variableDefinitions = "",
                fragmentDocument = translatedFragment,
                singleViaFilteredCollection = false,
            )
        assertTranslatedResponse(resolveGraphql(query), scenario.rootField)
    }

    private fun assertTranslatedResponse(
        raw: kotlinx.serialization.json.JsonElement,
        rootField: String,
    ) {
        assertNull(raw.jsonObject["errors"], raw.toString())
        val data = requireNotNull(raw.jsonObject["data"]) { raw.toString() }
        val restored = PgGraphqlTranslation.restoreViaductResponseShape(data)
        val connection = requireNotNull(restored.jsonObject[rootField]) { restored.toString() }.jsonObject
        val node = requireNotNull(connection["nodes"]) { restored.toString() }.jsonArray.single().jsonObject
        assertEquals("Core", node["nickname"]!!.jsonPrimitive.content)
        assertEquals("Ada", node["owner"]!!.jsonObject["displayName"]!!.jsonPrimitive.content)
        assertFalse(raw.toString().contains("\"nodes\""))
    }

    private fun scenario(suffix: String): Scenario {
        val personType = "PipelinePerson$suffix"
        val teamType = "PipelineTeam$suffix"
        val collectionType = "${teamType}Collection"
        return Scenario(
            personType = personType,
            teamType = teamType,
            collectionType = collectionType,
            graphql = schema(personType, teamType, collectionType),
        )
    }

    private fun schema(
        personType: String,
        teamType: String,
        collectionType: String,
    ): String =
        """
        interface Node { id: ID! }
        type $personType implements Node { id: ID!, displayName: String! }
        type $teamType implements Node { id: ID!, nickname: String!, owner: $personType! }
        type $collectionType { nodes: [$teamType!]! }
        """.trimIndent()

    private fun configuration(
        mapping: File,
        model: dev.viaduct.persistence.model.PersistenceModel,
        database: DatabaseConnectionDetails,
    ) = HibernateMetadataConfigurationFactory.create(
        HibernateMetadataConfigurationInput(
            mappingFile = mapping,
            classpath = classpath(),
            semanticModel = model,
            hibernateSettings =
                mapOf(
                    "hibernate.connection.url" to database.url,
                    "hibernate.connection.username" to database.user,
                    "hibernate.connection.password" to database.password,
                    "hibernate.connection.driver_class" to "org.postgresql.Driver",
                    "hibernate.boot.allow_jdbc_metadata_access" to "true",
                    "hibernate.temp.use_jdbc_metadata_defaults" to "true",
                ),
        ),
    )

    private fun translationSchema(graphql: String): PgGraphqlTranslationSchema {
        val collections =
            Parser()
                .parseDocument(graphql)
                .definitions
                .filterIsInstance<ObjectTypeDefinition>()
                .mapNotNull { definition ->
                    val nodes =
                        definition.fieldDefinitions.singleOrNull { it.name == "nodes" }
                            ?: return@mapNotNull null
                    definition.name to nodes.type.namedType()
                }.toMap()
        return PgGraphqlTranslationSchema(collections, emptyMap())
    }

    private fun Type<*>.namedType(): String =
        when (this) {
            is NonNullType -> type.namedType()
            is ListType -> type.namedType()
            is TypeName -> requireNotNull(name)
            else -> error("Unsupported GraphQL type $this")
        }

    private fun Connection.insertFixture(
        table: String,
        id: UUID,
        displayName: String,
    ) {
        val sql =
            "INSERT INTO \"public\".${quoteIdentifier(table)} " +
                "(_uuid_id, display_name) VALUES (?, ?)"
        JdbcOperations.execute(this, sql, id, displayName)
    }

    private fun Connection.insertTeam(
        table: String,
        id: UUID,
        ownerId: UUID,
    ) {
        JdbcOperations.execute(
            this,
            "INSERT INTO \"public\".${quoteIdentifier(table)} (_uuid_id, nickname, owner_id) VALUES (?, ?, ?)",
            id,
            "Core",
            ownerId,
        )
    }

    private fun Connection.resolveGraphql(query: String) =
        JdbcOperations.query(this, "SELECT graphql.resolve(?)", { result ->
            check(result.next())
            Json.parseToJsonElement(result.getString(1))
        }, query)

    private fun Connection.execute(sql: String) {
        JdbcOperations.execute(this, sql)
    }

    private fun assertTrueDynamic(classNames: List<String?>) {
        check(classNames.isNotEmpty())
        check(classNames.all { it == null }) { "Hibernate unexpectedly resolved a POJO: $classNames" }
    }

    private fun connectionDetailsOrSkip(): DatabaseConnectionDetails {
        val details =
            DatabaseConnectionDetails(
                url = System.getenv("PG_INTEGRATION_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:54322/postgres",
                user = System.getenv("PG_INTEGRATION_USER") ?: "postgres",
                password = System.getenv("PG_INTEGRATION_PASSWORD") ?: "postgres",
            )
        val available = runCatching { details.connect().use { } }.isSuccess
        assumeTrue(available, "Start local Supabase or set PG_INTEGRATION_JDBC_URL")
        return details
    }

    private fun classpath(): List<File> =
        System
            .getProperty("java.class.path")
            .split(File.pathSeparator)
            .map(::File)
            .filter(File::exists)

    private data class DatabaseConnectionDetails(
        val url: String,
        val user: String,
        val password: String,
    ) {
        fun connect(): Connection = DriverManager.getConnection(url, user, password)
    }

    private data class Scenario(
        val personType: String,
        val teamType: String,
        val collectionType: String,
        val graphql: String,
    ) {
        val entityNames: Set<String> = setOf(personType, teamType)
        val rootField: String = teamType.replaceFirstChar(Char::lowercaseChar) + "Collection"
    }
}
