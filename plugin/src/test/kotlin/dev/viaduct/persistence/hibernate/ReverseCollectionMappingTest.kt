package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceModelBuilder
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.w3c.dom.Element
import viaduct.graphql.schema.graphqljava.extensions.ViaductSchemaFactory
import kotlin.test.assertEquals

class ReverseCollectionMappingTest {
    @ParameterizedTest
    @ValueSource(strings = ["manager", "groupId"])
    fun `reverse collection reuses the owning column and its nullability`(field: String) {
        val owner = if (field == "manager") "manager: Group" else "groupId: ID! @idOf(type: \"Group\")"
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                directive @idOf(type: String!) on FIELD_DEFINITION
                type Group { id: ID!, members: [Person!]! }
                type Person { id: ID!, $owner }
                """.trimIndent(),
            )
        val mapping = PersistenceModelToHbmMapper.map(PersistenceModelBuilder().build(schema, setOf("Group", "Person")))
        val members =
            mapping.entities
                .single { it.entityName == "Group" }
                .attributes
                .filterIsInstance<HbmToManyMapping>()
                .single()
        val person =
            mapping.entities
                .single { it.entityName == "Person" }
                .attributes
                .filterIsInstance<HbmToOneMapping>()
                .single()
        val key = HbmXmlWriter().document(mapping).getElementsByTagName("key").item(0) as Element
        assertEquals(
            listOf(person.columnName, person.nullable, if (person.nullable) "false" else "true", field),
            listOf(members.keyColumnName, members.keyNullable, key.getAttribute("not-null"), person.name),
        )
    }
}
