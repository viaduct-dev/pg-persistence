package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.hibernate.HibernateSchemaModelWriter
import dev.viaduct.persistence.hibernate.ViaductImplicitNamingStrategy
import dev.viaduct.persistence.hibernate.ViaductPhysicalNamingStrategy
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class HibernateSchemaDiffTaskTest {
    @Test
    fun `schema diff renders YAML semantic non-null as NOT NULL`() {
        val projectDirectory = Files.createTempDirectory("hibernate-semantic-not-null").toFile()
        try {
            val schemaDirectory =
                projectDirectory.resolve("schema").apply {
                    check(mkdirs() || isDirectory)
                }
            schemaDirectory.resolve("Model.graphqls").writeText(
                "interface Node { id: ID! } type Group implements Node { id: ID!, name: String }",
            )
            val config = projectDirectory.resolve("pg-persistence.yaml")
            config.writeText("types:\n  Group:\n    fields:\n      name:\n        semanticNotNull: true\n")
            val generated = projectDirectory.resolve("generated")
            HibernateSchemaModelWriter().write(
                model = PersistenceSchemaModelLoader.build(schemaDirectory, config),
                outputDirectory = generated,
            )
            val diffFile = projectDirectory.resolve("schema-diff/review.h2.sql")
            val task = task(projectDirectory, schemaDirectory, generated, config, diffFile)

            task.diff()

            val diff = diffFile.readText()
            assertContains(diff, "name CLOB NOT NULL")
        } finally {
            projectDirectory.deleteRecursively()
        }
    }

    @Test
    fun `schema diff uses the in-memory Hibernate reference`() {
        val projectDirectory = Files.createTempDirectory("hibernate-schema-diff").toFile()
        try {
            val schemaDirectory =
                projectDirectory.resolve("schema").apply {
                    check(mkdirs() || isDirectory)
                }
            schemaDirectory.resolve("Model.graphqls").writeText(
                "interface Node { id: ID! } type Group implements Node { id: ID! }",
            )
            val mappingFile = projectDirectory.resolve("mapping.hbm.xml")
            mappingFile.writeText(mappingXml())
            val diffFile = projectDirectory.resolve("schema-diff/review.h2.sql")
            val task =
                ProjectBuilder
                    .builder()
                    .withProjectDir(projectDirectory)
                    .build()
                    .tasks
                    .create("hibernateSchemaDiffRaw", HibernateSchemaDiffTask::class.java)

            task.centralSchemaDirectory.set(schemaDirectory)
            task.mappingFile.set(mappingFile)
            task.modelClasspath.from(classpath())
            task.implicitNamingStrategyClassName.set(ViaductImplicitNamingStrategy::class.java.name)
            task.physicalNamingStrategyClassName.set(ViaductPhysicalNamingStrategy::class.java.name)
            task.metadataCustomizerClassNames.set(emptyList())
            task.targetUrl.set(
                "jdbc:h2:mem:hibernate-schema-diff;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            )
            task.targetUsername.set("sa")
            task.targetPassword.set("")
            task.diffFile.set(diffFile)

            task.diff()

            assertTrue(diffFile.isFile)
            assertContains(diffFile.readText(), "CREATE TABLE")
            assertContains(diffFile.readText(), "\"GROUPS\"")
        } finally {
            projectDirectory.deleteRecursively()
        }
    }

    private fun classpath(): List<File> =
        System
            .getProperty("java.class.path")
            .split(File.pathSeparator)
            .map(::File)
            .filter(File::exists)

    private fun task(
        projectDirectory: File,
        schemaDirectory: File,
        generated: File,
        config: File,
        diffFile: File,
    ): HibernateSchemaDiffTask =
        ProjectBuilder
            .builder()
            .withProjectDir(projectDirectory)
            .build()
            .tasks
            .create("hibernateSemanticNotNullDiff", HibernateSchemaDiffTask::class.java)
            .apply {
                centralSchemaDirectory.set(schemaDirectory)
                mappingFile.set(generated.resolve("resources/META-INF/viaduct-persistence.hbm.xml"))
                modelClasspath.from(classpath())
                persistenceConfigFile.from(config)
                implicitNamingStrategyClassName.set(ViaductImplicitNamingStrategy::class.java.name)
                physicalNamingStrategyClassName.set(ViaductPhysicalNamingStrategy::class.java.name)
                metadataCustomizerClassNames.set(emptyList())
                targetUrl.set("jdbc:h2:mem:semantic-not-null;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                targetUsername.set("sa")
                targetPassword.set("")
                this.diffFile.set(diffFile)
            }

    private fun mappingXml(): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <hibernate-mapping xmlns="http://www.hibernate.org/xsd/orm/hbm" schema="public">
          <class entity-name="Group" table="Group" lazy="false">
            <id name="internalId" type="uuid" column="internalId"><generator class="assigned"/></id>
            <property name="id" type="string" not-null="true" insert="false" update="false">
              <column name="id" not-null="true" sql-type="text"/>
            </property>
          </class>
        </hibernate-mapping>
        """.trimIndent()
}
