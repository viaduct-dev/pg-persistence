package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.gradle.HibernateSchemaDiffTask
import dev.viaduct.persistence.gradle.HibernateSchemaSnapshotTask
import dev.viaduct.persistence.gradle.PersistencePlacementRegistrar
import dev.viaduct.persistence.gradle.PersistenceSchemaModelLoader
import dev.viaduct.persistence.gradle.SchemaPlacementMigrationGuard
import dev.viaduct.persistence.hibernate.EffectiveHibernateModelBuilder
import dev.viaduct.persistence.hibernate.FreshSchemaSqlWriter
import dev.viaduct.persistence.hibernate.HibernateMetadataHandle
import dev.viaduct.persistence.hibernate.ViaductImplicitNamingStrategy
import dev.viaduct.persistence.hibernate.ViaductPhysicalNamingStrategy
import dev.viaduct.persistence.jdbc.JdbcOperations
import dev.viaduct.persistence.model.PersistenceModel
import dev.viaduct.persistence.pggraphql.overlay.PgGraphqlOverlay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.gradle.testfixtures.ProjectBuilder
import org.hibernate.boot.model.naming.Identifier
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Executes generated HBM, fresh DDL and overlays in a disposable, explicitly selected database. */
class TenantPlacementIntegrationTest {
    @Test
    fun `generated schemas support cross-module foreign keys and reciprocal collections`() =
        withFixture { fixture, database, _ ->
            fixture.populate(database)
            val query = """{ teamCollection { edges { node {
                lead { name } membersAssociations { edges { node { node { name } } } }
            } } } }"""
            val result = fixture.resolve(database, query)
            assertNull(result["errors"], result.toString())
            val team =
                result
                    .getValue("data")
                    .jsonObject
                    .getValue("teamCollection")
                    .jsonObject
                    .getValue("edges")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("node")
                    .jsonObject
            assertEquals(
                "Grace",
                team
                    .getValue("lead")
                    .jsonObject
                    .getValue("name")
                    .jsonPrimitive.content,
            )
            val member =
                team
                    .getValue("membersAssociations")
                    .jsonObject
                    .getValue("edges")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("node")
                    .jsonObject
                    .getValue("node")
                    .jsonObject
            assertEquals("Ada", member.getValue("name").jsonPrimitive.content)
        }

    @Test
    fun `schema move guard requires reviewed migration and preserves data after explicit move`() =
        withFixture { fixture, database, handle ->
            fixture.populate(database)
            SchemaPlacementMigrationGuard.verify(handle.metadata, database)
            val table = handle.metadata.getEntityBinding("Person").table
            val temporary = fixture.people + "_old"
            JdbcOperations.execute(database, "CREATE SCHEMA \"$temporary\"")
            try {
                JdbcOperations.execute(
                    database,
                    "ALTER TABLE \"${fixture.people}\".\"${table.name}\" SET SCHEMA \"$temporary\"",
                )
                assertContains(
                    assertFailsWith<IllegalArgumentException> {
                        SchemaPlacementMigrationGuard.verify(handle.metadata, database)
                    }.message.orEmpty(),
                    "data-preserving migration",
                )
                JdbcOperations.execute(
                    database,
                    "ALTER TABLE \"$temporary\".\"${table.name}\" SET SCHEMA \"${fixture.people}\"",
                )
                SchemaPlacementMigrationGuard.verify(handle.metadata, database)
                val countSql = "SELECT count(*) FROM \"${fixture.people}\".\"${table.name}\""
                assertEquals(
                    1L,
                    JdbcOperations.query(database, countSql, { rows ->
                        check(rows.next())
                        rows.getLong(1)
                    }),
                )
            } finally {
                JdbcOperations.execute(database, "DROP SCHEMA \"$temporary\" CASCADE")
            }
        }

    @Test
    fun `live diff leaves an unowned same-named table and its children untouched`() =
        withFixture { fixture, database, handle ->
            val person = handle.metadata.getEntityBinding("Person").table
            val unowned = "\"${fixture.orders}\".\"${person.name}\""
            JdbcOperations.execute(database, "CREATE TABLE $unowned (id integer PRIMARY KEY, note text UNIQUE)")
            JdbcOperations.execute(database, "INSERT INTO $unowned VALUES (1, 'keep me')")
            JdbcOperations.execute(database, "CREATE INDEX unowned_note_index ON $unowned (note)")
            JdbcOperations.execute(
                database,
                "ALTER TABLE \"${fixture.people}\".\"${person.name}\" DROP COLUMN name",
            )
            val changed = fixture.diff(database)
            assertTrue(
                Regex("(?im)^ALTER TABLE \\\"?${fixture.people}\\\"?\\.\\\"?${person.name}\\\"? ADD \\\"?name\\\"?")
                    .containsMatchIn(changed),
                changed,
            )
            assertFalse(changed.contains("${fixture.orders}.${person.name}"), changed)
            assertFalse(changed.contains("\"${fixture.orders}\".\"${person.name}\""), changed)
            assertFalse(changed.contains("unowned_note_index"), changed)
            JdbcOperations.execute(database, changed)
            assertEquals(
                "keep me",
                JdbcOperations.query(database, "SELECT note FROM $unowned", { rows ->
                    check(rows.next())
                    rows.getString(1)
                }),
            )
            val restored = fixture.diff(database)
            assertFalse(Regex("(?im)^(CREATE|ALTER|DROP) (TABLE|INDEX)").containsMatchIn(restored), restored)
        }

    @Test
    fun `snapshot and live diff distinguish customized same-named tables in separate schemas`() =
        withFixture(
            namingStrategy = TenantCollisionNamingStrategy::class.java.name,
            persistencePolicy = "types:\n  Reviewer:\n    unique: [[name]]\n    indexes: [[name]]\n",
        ) { fixture, database, handle ->
            val reviewer = handle.metadata.getEntityBinding("Reviewer").table
            val team = handle.metadata.getEntityBinding("Team").table
            assertEquals(reviewer.name, team.name)
            assertEquals(setOf(fixture.people, fixture.orders), setOf(reviewer.schema, team.schema))
            val snapshot = fixture.snapshot()
            val objects =
                Json
                    .parseToJsonElement(snapshot)
                    .jsonObject
                    .getValue("snapshot")
                    .jsonObject
                    .getValue("objects")
                    .jsonObject
            val schemas =
                objects.getValue("liquibase.structure.core.Schema").jsonArray.associate { entry ->
                    val schema = entry.jsonObject.getValue("schema").jsonObject
                    "liquibase.structure.core.Schema#${schema.getValue("snapshotId").jsonPrimitive.content}" to
                        schema.getValue("name").jsonPrimitive.content
                }
            val tables =
                objects.getValue("liquibase.structure.core.Table").jsonArray.map { entry ->
                    val table = entry.jsonObject.getValue("table").jsonObject
                    schemas.getValue(table.getValue("schema").jsonPrimitive.content) to
                        table.getValue("name").jsonPrimitive.content
                }
            val sharedSchemas = tables.filter { it.second == "shared_records" }.map { it.first }.toSet()
            assertEquals(setOf(fixture.people, fixture.orders), sharedSchemas)
            val unchanged = fixture.diff(database)
            assertFalse(Regex("(?im)^(CREATE|ALTER|DROP) TABLE").containsMatchIn(unchanged), unchanged)
            JdbcOperations.execute(database, "ALTER TABLE \"${fixture.people}\".shared_records DROP COLUMN name")
            val changed = fixture.diff(database)
            assertTrue(
                Regex("(?im)^ALTER TABLE \"?${fixture.people}\"?\\.\"?shared_records\"? ADD \"?name\"?")
                    .containsMatchIn(changed),
                changed,
            )
            assertFalse(Regex("(?im)^ALTER TABLE \"?${fixture.orders}\"?").containsMatchIn(changed), changed)
            JdbcOperations.execute(database, changed)
            val restored = fixture.diff(database)
            assertFalse(Regex("(?im)^(CREATE|ALTER|DROP) TABLE").containsMatchIn(restored), restored)
            fixture.populate(database)
            val result = fixture.resolve(database, "{ teamCollection { edges { node { lead { name } } } } }")
            assertNull(result["errors"], result.toString())
            assertContains(result.toString(), "Grace")
        }

    private fun withFixture(
        namingStrategy: String = ViaductPhysicalNamingStrategy::class.java.name,
        persistencePolicy: String? = null,
        test: (TenantDatabaseFixture, Connection, HibernateMetadataHandle) -> Unit,
    ) {
        val url = System.getenv("PG_KAN37_GENERATED_URL")
        assumeTrue(url != null, "Set PG_KAN37_GENERATED_URL to a disposable local database")
        requireNotNull(url)
        require(url.matches(Regex("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/kan37_generated_tests")))
        val password = System.getenv("PG_KAN37_GENERATED_PASSWORD") ?: "postgres"
        val settings =
            mapOf(
                "hibernate.connection.url" to url,
                "hibernate.connection.username" to "postgres",
                "hibernate.connection.password" to password,
                "hibernate.connection.driver_class" to "org.postgresql.Driver",
                "hibernate.boot.allow_jdbc_metadata_access" to "true",
            )
        DriverManager.getConnection(url, "postgres", password).use { database ->
            val fixture = TenantDatabaseFixture(namingStrategy)
            withGeneratedModel(
                fixture.sdl,
                settings,
                namingStrategy = namingStrategy,
                persistencePolicy = persistencePolicy,
                modelLoader = fixture::load,
            ) { model, _, generated, handle ->
                try {
                    fixture.create(database, model, generated, handle)
                    test(fixture, database, handle)
                } finally {
                    JdbcOperations.execute(database, "DROP SCHEMA IF EXISTS \"${fixture.orders}\" CASCADE")
                    JdbcOperations.execute(database, "DROP SCHEMA IF EXISTS \"${fixture.people}\" CASCADE")
                }
            }
        }
    }
}

private class TenantDatabaseFixture(
    private val namingStrategy: String,
) {
    private lateinit var schemaDirectory: File
    private lateinit var generatedDirectory: File
    private lateinit var placements: List<File>
    private var policyFile: File? = null
    private lateinit var associationInsert: String
    private val suffix =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(8)
    val people = "kan37_people_$suffix"
    val orders = "kan37_orders_$suffix"
    val sdl =
        """
        type Person implements Node { id: ID!, name: String!, teams: TeamConnection }
        type Reviewer implements Node { id: ID!, name: String! }
        type Team implements Node { id: ID!, lead: Reviewer, members: [Person!] }
        type TeamConnection { edges: [TeamEdge!]!, nodes: [Team!]! }
        type TeamEdge { cursor: String!, node: Team!, label: String }
        """.trimIndent()

    fun load(
        schema: File,
        policy: File?,
    ): PersistenceModel {
        val artifacts =
            listOf(listOf("Person", "Reviewer") to people, listOf("Team") to orders).map { (types, placement) ->
                schema.parentFile
                    .resolve(placement)
                    .apply { check(mkdirs()) }
                    .resolve(PersistencePlacementRegistrar.FILE_NAME)
                    .apply {
                        writeText(
                            buildJsonObject {
                                put("version", 1)
                                put("module", "com.example.$placement")
                                put("databaseSchema", placement)
                                put("types", JsonArray(types.map(::JsonPrimitive)))
                            }.toString(),
                        )
                    }
            }
        schemaDirectory = schema
        policyFile = policy
        placements = artifacts
        return PersistenceSchemaModelLoader.build(schema, policy, modulePlacementArtifacts = artifacts)
    }

    fun create(
        database: Connection,
        model: PersistenceModel,
        generated: File,
        handle: HibernateMetadataHandle,
    ) {
        generatedDirectory = generated
        val effective = EffectiveHibernateModelBuilder.build(handle.metadata, model)
        val collection = handle.metadata.collectionBindings.single { it.role == "Person.teams" }
        val join = collection.collectionTable
        val owner =
            collection.key.columns
                .single()
                .name
        val target = (collection.element as org.hibernate.mapping.SimpleValue).columns.single().name
        associationInsert = "INSERT INTO \"${join.schema}\".\"${join.name}\" (\"$owner\",\"$target\") VALUES (?,?)"
        val create = generated.resolve("schema-create.sql")
        FreshSchemaSqlWriter.write(handle.metadata, create)
        JdbcOperations.execute(database, PostgresqlOverlay.renderPrerequisites(effective))
        JdbcOperations.execute(database, create.readText())
        JdbcOperations.execute(database, PostgresqlOverlay.renderMigration(effective))
        JdbcOperations.execute(database, PostgresqlOverlay.renderRepeatable(effective))
        JdbcOperations.execute(database, PgGraphqlOverlay.render(effective))
        JdbcOperations.execute(database, "SELECT set_config('search_path', ?, false)", "\"$people\",\"$orders\",public")
    }

    fun snapshot(): String {
        val project = ProjectBuilder.builder().withProjectDir(generatedDirectory).build()
        val output = generatedDirectory.resolve("snapshot.json")
        project.tasks.register("snapshot", HibernateSchemaSnapshotTask::class.java).get().apply {
            centralSchemaDirectory.set(schemaDirectory)
            persistenceConfigFile.from(listOfNotNull(policyFile))
            modulePlacementArtifacts.from(placements)
            mappingFile.set(generatedDirectory.resolve("resources/META-INF/viaduct-persistence.hbm.xml"))
            modelClasspath.from(System.getProperty("java.class.path").split(File.pathSeparator).map(::File))
            implicitNamingStrategyClassName.set(ViaductImplicitNamingStrategy::class.java.name)
            physicalNamingStrategyClassName.set(namingStrategy)
            snapshotFile.set(output)
            snapshot()
        }
        return output.readText()
    }

    fun diff(database: Connection): String {
        val project = ProjectBuilder.builder().withProjectDir(generatedDirectory).build()
        val output = generatedDirectory.resolve("diff.postgresql.sql")
        project.tasks.register("diff", HibernateSchemaDiffTask::class.java).get().apply {
            centralSchemaDirectory.set(schemaDirectory)
            persistenceConfigFile.from(listOfNotNull(policyFile))
            modulePlacementArtifacts.from(placements)
            mappingFile.set(generatedDirectory.resolve("resources/META-INF/viaduct-persistence.hbm.xml"))
            modelClasspath.from(System.getProperty("java.class.path").split(File.pathSeparator).map(::File))
            implicitNamingStrategyClassName.set(ViaductImplicitNamingStrategy::class.java.name)
            physicalNamingStrategyClassName.set(namingStrategy)
            targetUrl.set(database.metaData.url)
            targetUsername.set("postgres")
            targetPassword.set(System.getenv("PG_KAN37_GENERATED_PASSWORD") ?: "postgres")
            diffFile.set(output)
            diff()
        }
        return output.takeIf(File::exists)?.readText().orEmpty()
    }

    fun populate(database: Connection) {
        val person = UUID.randomUUID().toString()
        val reviewer = UUID.randomUUID().toString()
        val team = UUID.randomUUID().toString()
        val inserted =
            resolve(
                database,
                """mutation {
            insertIntoPersonCollection(objects:[{uuidId:"$person",name:"Ada"}]) { affectedCount }
            insertIntoReviewerCollection(objects:[{uuidId:"$reviewer",name:"Grace"}]) { affectedCount }
            insertIntoTeamCollection(objects:[{uuidId:"$team",leadId:"$reviewer"}]) { affectedCount }
        }""",
            )
        assertNull(inserted["errors"], inserted.toString())
        // Canonical reciprocal owner is Person. The generated association is also in people.
        JdbcOperations.execute(database, associationInsert, UUID.fromString(person), UUID.fromString(team))
    }

    fun resolve(
        database: Connection,
        query: String,
    ): JsonObject =
        JdbcOperations.query(database, "SELECT graphql.resolve(?)", { rows ->
            check(rows.next())
            Json.parseToJsonElement(rows.getString(1)).jsonObject
        }, query)
}

/** Deliberately gives two original types one physical name, qualified by their owning schema. */
class TenantCollisionNamingStrategy : ViaductPhysicalNamingStrategy() {
    override fun toPhysicalTableName(
        logicalName: Identifier,
        jdbcEnvironment: JdbcEnvironment,
    ): Identifier =
        if (logicalName.text in setOf("Reviewer", "Team")) {
            Identifier.toIdentifier("shared_records")
        } else {
            super.toPhysicalTableName(logicalName, jdbcEnvironment)
        }
}
