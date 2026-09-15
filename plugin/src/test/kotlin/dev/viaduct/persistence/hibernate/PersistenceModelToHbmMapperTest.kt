package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceBasicAttribute
import dev.viaduct.persistence.model.PersistenceEntity
import dev.viaduct.persistence.model.PersistenceModel
import dev.viaduct.persistence.model.PersistenceToOneAttribute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistenceModelToHbmMapperTest {
    @Test
    fun `maps semantic objects without involving XML formatting`() {
        val model =
            PersistenceModel(
                entities =
                    listOf(
                        PersistenceEntity(
                            graphqlName = "Team",
                            generatedGlobalId = true,
                            attributes =
                                listOf(
                                    PersistenceBasicAttribute("internalId", false, "java.util.UUID"),
                                    PersistenceBasicAttribute("id", false, "String"),
                                    PersistenceBasicAttribute("score", true, "Int"),
                                    PersistenceToOneAttribute("owner", false, "Person"),
                                ),
                        ),
                    ),
                enums = emptyList(),
            )

        val entity = PersistenceModelToHbmMapper.map(model).entities.single()
        assertEquals("Team", entity.entityName)
        assertEquals("Team", entity.tableName)
        val attributes = entity.attributes.associateBy { it.name }
        val internalId = attributes.getValue("internalId") as HbmBasicMapping
        assertTrue(internalId.primaryKey)
        assertEquals("uuid", internalId.hibernateType)
        val id = attributes.getValue("id") as HbmBasicMapping
        assertFalse(id.insertable)
        assertFalse(id.updatable)
        assertEquals("string", id.hibernateType)
        assertEquals("integer", (attributes.getValue("score") as HbmBasicMapping).hibernateType)
        val owner = attributes.getValue("owner") as HbmToOneMapping
        assertEquals("Person", owner.targetEntityName)
        assertEquals("ownerId", owner.columnName)
    }
}
