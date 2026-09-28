package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.gradle.PersistenceSchemaModelLoader
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers schema discovery, HBM generation, naming, and Hibernate bootstrap as one pipeline. */
class DynamicHbmIntegrationTest {
    @Test
    fun `boots GRT-shaped dynamic entities with semantic nullability and relationships`() {
        val directory = Files.createTempDirectory("dynamic-hbm-integration").toFile()
        try {
            val schema = directory.resolve("schema").apply { check(mkdirs()) }
            schema.resolve("Model.graphqls").writeText(
                """
                interface Node { id: ID! }
                type Person implements Node { id: ID!, displayName: String }
                type Team implements Node { id: ID!, owner: Person!, nickname: String }
                """.trimIndent(),
            )
            val policy = directory.resolve("pg-persistence.yaml")
            policy.writeText("types:\n  Team:\n    fields:\n      nickname:\n        semanticNotNull: true\n")
            val model = PersistenceSchemaModelLoader.build(schema, policy)
            val generated = directory.resolve("generated")
            HibernateSchemaModelWriter().write(model, generated)
            val mapping = generated.resolve("resources/META-INF/viaduct-persistence.hbm.xml")
            val configuration =
                HibernateMetadataConfigurationFactory.create(
                    HibernateMetadataConfigurationInput(
                        mappingFile = mapping,
                        classpath = classpath(),
                        semanticModel = model,
                    ),
                )

            HibernateMetadataBootstrap.build(configuration).use { handle ->
                val bindings = handle.metadata.entityBindings.associateBy { it.entityName }
                assertEquals(setOf("Person", "Team"), bindings.keys)
                assertTrue(bindings.values.all { it.className == null })

                val team = requireNotNull(bindings["Team"])
                assertEquals("teams", team.table.name)
                val nickname = team.getProperty("nickname")
                assertFalse(nickname.isOptional)
                assertFalse(
                    nickname.value.columns
                        .single()
                        .isNullable,
                )

                val owner = team.getProperty("owner")
                assertEquals(
                    "owner_id",
                    owner.value.columns
                        .single()
                        .name,
                )
            }

            assertFalse(generated.resolve("kotlin").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun classpath(): List<File> =
        System
            .getProperty("java.class.path")
            .split(File.pathSeparator)
            .map(::File)
            .filter(File::exists)
}
