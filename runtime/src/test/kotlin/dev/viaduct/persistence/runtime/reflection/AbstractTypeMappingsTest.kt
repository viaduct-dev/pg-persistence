package dev.viaduct.persistence.runtime.reflection

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AbstractTypeMappingsTest {
    private val reference = AbstractRelationship("Activity", "subject", "Subject", setOf("Person", "Group"), true)

    @Test
    fun `lookup is scoped by both owner and field`() {
        val other = reference.copy(ownerType = "Event", nullable = false)
        val mappings = mappings(reference, other)
        assertEquals(other, mappings.relationship("Event", "subject"))
        assertNull(mappings.relationship("Event", "missing"))
    }

    @Test
    fun `duplicate coordinates fail at construction`() {
        val duplicate = reference.copy(nullable = false)
        val failure = assertFailsWith<IllegalArgumentException> { mappings(reference, duplicate) }
        assertEquals("Duplicate abstract relationship Activity.subject", failure.message)
    }

    @Test
    fun `duplicate coordinates fail when loading serialized metadata`() {
        val relation = Json.encodeToString(AbstractRelationship.serializer(), reference)
        val json = """{"possibleTypes":{"Subject":["Person","Group"]},"relationships":[$relation,$relation]}"""
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(AbstractTypeMappings.serializer(), json) }
    }

    @Test
    fun `targets must match the declared possible concrete types`() {
        assertFailsWith<IllegalArgumentException> { mappings(reference.copy(targets = setOf("Person"))) }
    }

    @Test
    fun `stored references must have targets`() {
        assertFailsWith<IllegalArgumentException> { mappings(reference.copy(targets = emptySet())) }
    }

    @Test
    fun `connection metadata requires a collection and an edge type`() {
        assertFailsWith<IllegalArgumentException> { mappings(reference.copy(connectionType = "SubjectConnection")) }
    }

    @Test
    fun `serialization excludes the index and reconstructs it on load`() {
        val original = mappings(reference)
        val json = original.encode()
        assertFalse(json.contains("relationshipsByCoordinate"))
        val restored = Json.decodeFromString(AbstractTypeMappings.serializer(), json)
        assertEquals(reference, restored.relationship("Activity", "subject"))
    }

    @Test
    fun `copy builds a new lookup index`() {
        val copy = mappings(reference).copy(relationships = listOf(reference.copy(fieldName = "actor")))
        assertEquals("actor", copy.relationship("Activity", "actor")?.fieldName)
        assertNull(copy.relationship("Activity", "subject"))
    }

    @Test
    fun `mapping snapshots nested possible types`() {
        val targets = mutableSetOf("Person", "Group")
        val possibleTypes = mutableMapOf("Subject" to targets)
        val original = AbstractTypeMappings(possibleTypes = possibleTypes, relationships = listOf(reference))
        targets.clear()
        possibleTypes.clear()
        assertEquals(mappings(reference), original)
    }

    @Test
    fun `mapping snapshots relationship lists before indexing`() {
        val relationships = mutableListOf(reference)
        val original =
            AbstractTypeMappings(
                possibleTypes = mapOf("Subject" to reference.targets),
                relationships = relationships,
            )
        relationships.clear()
        assertEquals(original.relationships.single(), original.relationship("Activity", "subject"))
    }

    @Test
    fun `relationship copy snapshots replacement targets`() {
        val targets = mutableSetOf("Person")
        val copied = reference.copy(targets = targets)
        targets.clear()
        assertEquals(setOf("Person"), copied.targets)
    }

    @Test
    fun `mapping copy snapshots replacement relationships`() {
        val relationships = mutableListOf(reference.copy(fieldName = "actor"))
        val copied = mappings(reference).copy(relationships = relationships)
        relationships.clear()
        assertEquals(copied.relationships.single(), copied.relationship("Activity", "actor"))
    }

    @Test
    fun `deserialized possible type sets are immutable`() {
        val restored = Json.decodeFromString(AbstractTypeMappings.serializer(), mappings(reference).encode())
        assertFailsWith<UnsupportedOperationException> {
            (restored.possibleTypes.getValue("Subject") as MutableSet).clear()
        }
    }

    @Test
    fun `deserialized relationship targets are immutable`() {
        val restored = Json.decodeFromString(AbstractTypeMappings.serializer(), mappings(reference).encode())
        assertFailsWith<UnsupportedOperationException> {
            (restored.relationships.single().targets as MutableSet).clear()
        }
    }

    @Test
    fun `deserialized relationship list is immutable`() {
        val restored = Json.decodeFromString(AbstractTypeMappings.serializer(), mappings(reference).encode())
        assertFailsWith<UnsupportedOperationException> { (restored.relationships as MutableList).clear() }
    }

    @Test
    fun `possible types map is immutable`() {
        val original = mappings(reference)
        assertFailsWith<UnsupportedOperationException> { (original.possibleTypes as MutableMap).clear() }
    }

    @Test
    fun `serialization preserves ordering defaults and value semantics`() {
        val json =
            """{"possibleTypes":{"Subject":["Person","Group"]},"relationships":[""" +
                """{"ownerType":"Activity","fieldName":"subject","abstractType":"Subject",""" +
                """"targets":["Person","Group"],"nullable":true}]}"""
        val decoded = Json.decodeFromString(AbstractTypeMappings.serializer(), json)
        assertEquals(json, decoded.encode())
        assertEquals(decoded, decoded.copy())
        assertEquals(decoded.hashCode(), decoded.copy().hashCode())
    }

    @Test
    fun `cached mappings cannot be changed through returned collections`(
        @TempDir directory: Path,
    ) {
        val original = mappings(reference)
        val resource = directory.resolve(AbstractTypeMappings.RESOURCE)
        Files.createDirectories(requireNotNull(resource.parent))
        Files.writeString(resource, original.encode())
        URLClassLoader(arrayOf(directory.toUri().toURL()), null).use { loader ->
            val loaded = AbstractTypeMappings.load(loader)
            assertFailsWith<UnsupportedOperationException> {
                (loaded.possibleTypes.getValue("Subject") as MutableSet).clear()
            }
            assertEquals(original, AbstractTypeMappings.load(loader))
        }
    }

    private fun mappings(vararg relationships: AbstractRelationship) =
        AbstractTypeMappings(
            possibleTypes = mapOf("Subject" to reference.targets),
            relationships = relationships.toList(),
        )
}
