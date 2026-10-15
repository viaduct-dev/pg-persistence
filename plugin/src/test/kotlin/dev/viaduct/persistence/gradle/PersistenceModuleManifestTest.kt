package dev.viaduct.persistence.gradle

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersistenceModuleManifestTest {
    @TempDir
    private lateinit var directory: File

    @Test
    fun `external modules need no persistence plugin or individual exclusions`() {
        val (schema, placement) = fixture()
        val model = PersistenceSchemaModelLoader.build(schema, null, modulePlacementArtifacts = listOf(placement))
        assertEquals(listOf("Person"), model.entities.map { it.graphqlName })
        assertEquals("people", model.entities.single().databaseSchema)
        assertContains(
            model.entities
                .single()
                .attributes
                .map { it.name },
            "extra",
        )
    }

    @Test
    fun `required module artifact cannot disappear even when external modules are present`() {
        val (schema, _) = fixture()
        assertContains(
            assertFailsWith<IllegalArgumentException> {
                PersistenceSchemaModelLoader.build(schema, null)
            }.message.orEmpty(),
            "Missing persistence placement artifacts for modules: example.people",
        )
    }

    @Test
    fun `incomplete ownership does not turn a persisted type into an external type`() {
        val (schema, placement) = fixture(emptyList())
        assertContains(
            assertFailsWith<IllegalArgumentException> {
                PersistenceSchemaModelLoader.build(schema, null, modulePlacementArtifacts = listOf(placement))
            }.message.orEmpty(),
            "Missing persistence placement metadata for Person",
        )
    }

    @Test
    fun `single project SDL artifacts share the application's local persistence ownership`() {
        val (schema, placement) = fixture()
        val people = schema.resolve("People.graphqls")
        val external = schema.resolve("External.graphqls")
        val partition = directory.resolve("build/schemaPartition").apply { check(mkdirs()) }
        people.copyTo(partition.resolve(people.name))
        PersistenceModuleManifest.write(
            schema.resolve(".persistence"),
            listOf(listOf(partition), listOf(external)),
            listOf(people, placement),
        )
        assertEquals(
            listOf("Person"),
            PersistenceSchemaModelLoader
                .build(schema, null, modulePlacementArtifacts = listOf(placement))
                .entities
                .map { it.graphqlName },
        )
    }

    @Test
    fun `all external application generates no storage`() {
        val schema = directory.resolve("schema").apply { check(mkdirs()) }
        val external =
            schema.resolve("External.graphqls").apply {
                writeText("type External implements Node { id: ID! }")
            }
        PersistenceModuleManifest.write(schema.resolve(".persistence"), listOf(listOf(external)))
        assertEquals(emptyList(), PersistenceSchemaModelLoader.build(schema, null).entities)
    }

    @Test
    fun `external and persistent ownership may not overlap`() {
        val (schema, placement) = fixture()
        val duplicate =
            directory.resolve("Duplicate.graphqls").apply {
                writeText("type Person implements Node { id: ID! }")
            }
        PersistenceModuleManifest.write(
            schema.resolve(".persistence"),
            listOf(listOf(placement), listOf(duplicate)),
        )
        assertContains(
            assertFailsWith<IllegalArgumentException> {
                PersistenceSchemaModelLoader.build(schema, null, modulePlacementArtifacts = listOf(placement))
            }.message.orEmpty(),
            "Conflicting external and persistent type ownership",
        )
    }

    private fun fixture(types: List<String> = listOf("Person")): Pair<File, File> {
        val schema = directory.resolve("schema").apply { check(mkdirs()) }
        val people =
            schema.resolve("People.graphqls").apply {
                writeText("type Person implements Node { id: ID!, name: String }")
            }
        val external =
            schema.resolve("External.graphqls").apply {
                writeText("type External implements Node { id: ID! }\nextend type Person { extra: String }")
            }
        val placement =
            directory.resolve(PersistencePlacementRegistrar.FILE_NAME).apply {
                writeText(
                    buildJsonObject {
                        put("version", 1)
                        put("module", "example.people")
                        put("databaseSchema", "people")
                        put("types", JsonArray(types.map(::JsonPrimitive)))
                    }.toString(),
                )
            }
        PersistenceModuleManifest.write(
            schema.resolve(".persistence"),
            listOf(listOf(people, placement), listOf(external)),
        )
        return schema to placement
    }
}
