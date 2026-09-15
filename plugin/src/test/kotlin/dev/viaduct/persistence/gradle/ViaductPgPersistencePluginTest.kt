package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.io.ensureDirectory
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViaductPgPersistencePluginTest {
    @Test
    fun `persistence YAML changes invalidate generation and update mappings`() {
        val projectDirectory = Files.createTempDirectory("viaduct-persistence-policy-input").toFile()
        try {
            writeConsumerFiles(projectDirectory, "policy-input-consumer", effectiveBuildScript())
            writeSchema(
                projectDirectory,
                "interface Node { id: ID! } type Group implements Node { id: ID!, name: String }",
            )

            val first = runGradle(projectDirectory, "generateViaductPgPersistenceModel")
            assertEquals(TaskOutcome.SUCCESS, first.task(":generateViaductPgPersistenceModel")?.outcome)
            val second = runGradle(projectDirectory, "generateViaductPgPersistenceModel")
            assertEquals(TaskOutcome.UP_TO_DATE, second.task(":generateViaductPgPersistenceModel")?.outcome)

            val config = projectDirectory.resolve("src/main/viaduct/persistence.yaml")
            config.parentFile.ensureDirectory()
            config.writeText("semanticNotNull:\n  fields: [Group.name]\n")
            val third = runGradle(projectDirectory, "generateViaductPgPersistenceModel")

            assertEquals(TaskOutcome.SUCCESS, third.task(":generateViaductPgPersistenceModel")?.outcome)
            val mapping =
                projectDirectory
                    .resolve("build/generated/viaduct-persistence/resources/META-INF/viaduct-persistence.hbm.xml")
                    .readText()
            assertContains(mapping, "<column name=\"name\" not-null=\"true\"/>")
            assertFalse(projectDirectory.resolve("build/generated/viaduct-persistence/kotlin").exists())
        } finally {
            projectDirectory.deleteRecursively()
        }
    }

    @Test
    fun `generates effective metadata in a synthetic consumer`() {
        val projectDirectory =
            Files.createTempDirectory("viaduct-persistence-consumer").toFile()
        try {
            writeConsumerFiles(projectDirectory, "synthetic-consumer", effectiveBuildScript())
            writeSchema(projectDirectory, effectiveSchema())
            runGradle(
                projectDirectory,
                "buildViaductEffectiveModel",
                "hibernateSchemaSnapshot",
            )
            assertEffectiveModel(projectDirectory)
        } finally {
            projectDirectory.deleteRecursively()
        }
    }

    @Test
    fun `works in a module-only consumer with no assembleViaductCentralSchema task`() {
        val projectDirectory =
            Files.createTempDirectory("viaduct-persistence-module-only-consumer").toFile()
        try {
            writeConsumerFiles(projectDirectory, "module-only-consumer", moduleOnlyBuildScript())
            val moduleSchemaDir = projectDirectory.resolve("src/main/viaduct/schema")
            moduleSchemaDir.ensureDirectory()
            moduleSchemaDir.resolve("Model.graphqls").writeText(effectiveSchema())
            runGradle(
                projectDirectory,
                "generateViaductPgPersistenceModel",
            )
            val mapping =
                projectDirectory
                    .resolve("build/generated/viaduct-persistence/resources/META-INF/viaduct-persistence.hbm.xml")
                    .readText()
            assertContains(mapping, "entity-name=\"Group\"")
            assertContains(mapping, "table=\"Group\"")
        } finally {
            projectDirectory.deleteRecursively()
        }
    }

    @Test
    fun `generates association-backed edge fields in the effective model`() {
        val projectDirectory =
            Files.createTempDirectory("viaduct-persistence-edge-consumer").toFile()
        try {
            writeConsumerFiles(projectDirectory, "edge-consumer", effectiveBuildScript())
            writeSchema(projectDirectory, edgeSchema())
            runGradle(projectDirectory, "buildViaductEffectiveModel")
            val output = projectDirectory.resolve("build/generated/viaduct-effective-model/META-INF")
            val pgGraphql = output.resolve("pg-graphql-overlay.sql").readText()
            val postgresql = output.resolve("postgresql-migration.sql").readText()
            assertContains(pgGraphql, "COMMENT ON TABLE \"public\".\"group_members_associations\"")
            assertContains(pgGraphql, "membersAssociations")
            assertContains(pgGraphql, "invitedBy")
            assertContains(postgresql, "ADD COLUMN \"role\" varchar(255) NOT NULL")
            assertContains(postgresql, "FOREIGN KEY (\"invited_by_id\")")
            assertContains(postgresql, "REFERENCES \"public\".\"persons\" (\"_uuid_id\")")
            assertFalse(pgGraphql.contains("CREATE OR REPLACE VIEW"))
            assertFalse(pgGraphql.contains("CREATE OR REPLACE FUNCTION"))
            assertFalse(output.resolve("viaduct-effective-model.tsv").exists())
        } finally {
            projectDirectory.deleteRecursively()
        }
    }

    @Test
    fun `matching object and @idOf fields share a foreign key`() {
        val projectDirectory =
            Files.createTempDirectory("viaduct-persistence-idof-consumer").toFile()
        try {
            writeConsumerFiles(projectDirectory, "idof-consumer", effectiveBuildScript())
            writeSchema(projectDirectory, idOfSchema())
            runGradle(
                projectDirectory,
                "buildViaductEffectiveModel",
                "hibernateSchemaSnapshot",
            )
            val mapping =
                projectDirectory
                    .resolve("build/generated/viaduct-persistence/resources/META-INF/viaduct-persistence.hbm.xml")
                    .readText()
            assertContains(mapping, "<many-to-one entity-name=\"Group\"")
            assertContains(mapping, "name=\"group\"")
            assertContains(mapping, "<column name=\"groupId\"")
            assertFalse(mapping.contains("groupIdId"))
            val sql =
                projectDirectory
                    .resolve("build/generated/viaduct-effective-model/META-INF/pg-graphql.sql")
                    .readText()
            assertContains(sql, "FOREIGN KEY (\"group_id\")")
            assertContains(sql, "REFERENCES \"public\".\"groups\"")
            assertContains(sql, "\"foreign_name\": \"group\"")
        } finally {
            projectDirectory.deleteRecursively()
        }
    }

    @Test
    fun `generated resource source directories carry their task dependency`() {
        val projectDirectory =
            Files.createTempDirectory("viaduct-persistence-resources").toFile()
        try {
            writeConsumerFiles(projectDirectory, "resource-consumer", resourceBuildScript())
            writeSchema(projectDirectory, "interface Node { id: ID! }\ntype Group implements Node { id: ID! }")
            val result = runGradle(projectDirectory, "inspectGeneratedResources")
            assertTrue(result.output.contains("> Task :generateViaductPgPersistenceModel"))
        } finally {
            projectDirectory.deleteRecursively()
        }
    }

    private fun writeConsumerFiles(
        directory: java.io.File,
        projectName: String,
        buildScript: String,
    ) {
        directory.resolve("settings.gradle.kts").writeText(settingsScript(projectName))
        directory.resolve("build.gradle.kts").writeText(buildScript)
    }

    private fun writeSchema(
        directory: java.io.File,
        schema: String,
    ) {
        directory.resolve("schema").ensureDirectory()
        directory.resolve("schema/Model.graphqls").writeText(schema)
    }

    private fun runGradle(
        directory: java.io.File,
        vararg arguments: String,
    ) = GradleRunner
        .create()
        .withProjectDir(directory)
        .withPluginClasspath()
        .withArguments(*arguments, "--stacktrace")
        .build()

    private fun assertEffectiveModel(directory: java.io.File) {
        val mapping =
            directory
                .resolve(
                    "build/generated/viaduct-persistence/" +
                        "resources/META-INF/viaduct-persistence.hbm.xml",
                ).readText()
        assertContains(mapping, "entity-name=\"Group\"")
        assertContains(mapping, "table=\"Group\"")
        assertContains(mapping, "column=\"groupId\"")
        val generatedMetaInf =
            directory.resolve("build/generated/viaduct-persistence/resources/META-INF")
        assertTrue(generatedMetaInf.listFiles().orEmpty().none { it.name.startsWith("pg-graphql-translation-schema") })
        assertTrue(generatedMetaInf.listFiles().orEmpty().none { it.name.endsWith(".tsv") })
        assertFalse(directory.walkTopDown().any { it.name.startsWith("hibernate-reference") })
        assertTrue(directory.resolve("build/schema-diff/hibernate-snapshot.json").isFile)
    }

    private fun settingsScript(projectName: String): String =
        """
        pluginManagement {
            repositories {
                mavenLocal()
                gradlePluginPortal()
            }
        }
        dependencyResolutionManagement {
            repositories {
                mavenLocal()
                mavenCentral()
            }
        }
        rootProject.name = "$projectName"
        """.trimIndent()

    private fun effectiveBuildScript(): String =
        """
        plugins {
            kotlin("jvm") version "2.1.0"
            id("dev.viaduct.pg-persistence")
        }

        tasks.register("assembleViaductCentralSchema")

        viaductPgPersistence {
            centralSchemaDirectory.set(file("schema"))
        }
        """.trimIndent()

    /**
     * No `assembleViaductCentralSchema` task registered at all — simulates a Viaduct *module*
     * project (e.g. a dedicated persistence tenant) with no sibling application in the same
     * Gradle project. `centralSchemaDirectory` is left unset so it falls back to its
     * `src/main/viaduct/schema` convention.
     */
    private fun moduleOnlyBuildScript(): String =
        """
        plugins {
            kotlin("jvm") version "2.1.0"
            id("dev.viaduct.pg-persistence")
        }

        """.trimIndent()

    private fun resourceBuildScript(): String =
        """
        plugins {
            kotlin("jvm") version "2.1.0"
            id("dev.viaduct.pg-persistence")
        }

        tasks.register("assembleViaductCentralSchema")

        viaductPgPersistence {
            centralSchemaDirectory.set(file("schema"))
        }

        val mainResources = sourceSets.main.get().resources.sourceDirectories
        tasks.register("inspectGeneratedResources") {
            inputs.files(mainResources)
            doLast {
                check(mainResources.files.any {
                    it.resolve("META-INF/viaduct-persistence.hbm.xml").isFile
                })
            }
        }
        """.trimIndent()

    private fun effectiveSchema(): String =
        """
        directive @connection on OBJECT
        directive @edge on OBJECT

        interface Node {
          id: ID!
        }

        type Group implements Node {
          id: ID!
          name: String!
          labels: [String!]!
          members: PersonPage!
        }

        type Person implements Node {
          id: ID!
        }

        type PersonPage {
          edges: [PersonLink!]!
        }

        type PersonLink {
          node: Person!
        }

        type Query {
          nodes: [Group!]!
        }
        """.trimIndent()

    private fun idOfSchema(): String =
        """
        directive @idOf(type: String!) on FIELD_DEFINITION

        interface Node {
          id: ID!
        }

        type Group implements Node {
          id: ID!
        }

        type Person implements Node {
          id: ID!
          group: Group
          groupId: ID @idOf(type: "Group")
        }

        type Query {
          nodes: [Person!]!
        }
        """.trimIndent()

    private fun edgeSchema(): String =
        """
        directive @connection on OBJECT
        directive @edge on OBJECT

        interface Node {
          id: ID!
        }

        type Group implements Node {
          id: ID!
          members: PersonPage!
        }

        type Person implements Node {
          id: ID!
        }

        type PersonPage @connection {
          edges: [PersonLink!]!
        }

        type PersonLink @edge {
          node: Person!
          role: String!
          invitedBy: Person
        }

        type Query {
          nodes: [Group!]!
        }
        """.trimIndent()
}
