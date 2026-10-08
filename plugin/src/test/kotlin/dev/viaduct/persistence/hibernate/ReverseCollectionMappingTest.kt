package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceModelBuilder
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.w3c.dom.Element
import viaduct.graphql.schema.graphqljava.extensions.ViaductSchemaFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class ReverseCollectionMappingTest {
    @ParameterizedTest
    @CsvSource("manager,true", "manager,false", "groupId,true", "groupId,false", "teamId,true", "teamId,false")
    fun `reverse collection reuses the owning column and nullability`(
        field: String,
        nullable: Boolean,
    ) {
        val required = if (nullable) "" else "!"
        val owner = if (field == "manager") "manager: Team$required" else "$field: ID$required @idOf(type: \"Team\")"
        val mapping =
            mapping(
                """
                directive @idOf(type: String!) on FIELD_DEFINITION
                type Team { id: ID!, members: [Person!]! }
                type Person { id: ID!, $owner }
                """.trimIndent(),
                setOf("Team", "Person"),
            )
        val members =
            mapping.entities
                .single { it.entityName == "Team" }
                .attributes
                .filterIsInstance<HbmToManyMapping>()
                .single()
        val key = HbmXmlWriter().document(mapping).getElementsByTagName("key").item(0) as Element
        assertEquals(
            listOf(if (field == "manager") "managerId" else field, (!nullable).toString()),
            listOf(members.keyColumnName, key.getAttribute("not-null")),
        )
    }

    @Test
    fun `self reverse collection shares its nullable manager column`() {
        val mapping = mapping("type Person { id: ID!, manager: Person, reports: [Person!]! }", setOf("Person"))
        val key = HbmXmlWriter().document(mapping).getElementsByTagName("key").item(0) as Element
        assertEquals(listOf("managerId", "false"), listOf(key.getAttribute("column"), key.getAttribute("not-null")))
    }

    @Test
    fun `unpaired FK and join collections retain their existing keys`() {
        val mapping =
            mapping(
                """
                type Team { id: ID!, members: [Person!]!, labels: [Label!]!, featuredLabels: [Label!]! }
                type Person { id: ID! }
                type Label { id: ID! }
                """.trimIndent(),
                setOf("Team", "Person", "Label"),
            )
        val keys = HbmXmlWriter().document(mapping).getElementsByTagName("key")
        assertEquals(
            List(3) { listOf("teamId", "true") },
            (0 until keys.length).map { index ->
                val key = keys.item(index) as Element
                listOf(key.getAttribute("column"), key.getAttribute("not-null"))
            },
        )
    }

    private fun mapping(
        sdl: String,
        types: Set<String>,
    ): HbmMappingDocument =
        PersistenceModelToHbmMapper.map(
            PersistenceModelBuilder().build(ViaductSchemaFactory.fromTypeDefinitionRegistry(sdl), types),
        )
}
