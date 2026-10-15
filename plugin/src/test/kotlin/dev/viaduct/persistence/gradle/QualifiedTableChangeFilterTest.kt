package dev.viaduct.persistence.gradle

import liquibase.structure.core.Column
import liquibase.structure.core.ForeignKey
import liquibase.structure.core.Index
import liquibase.structure.core.PrimaryKey
import liquibase.structure.core.Schema
import liquibase.structure.core.Table
import liquibase.structure.core.UniqueConstraint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QualifiedTableChangeFilterTest {
    @Test
    fun `table identities are literal and foreign keys belong to their source table`() {
        val filter = QualifiedTableChangeFilter(setOf("core" to "group.records", "google" to "purchases"))
        val owned =
            Table().apply {
                name = "group.records"
                schema = Schema(null as String?, "core")
            }
        val unowned =
            Table().apply {
                name = "group.records"
                schema = Schema(null as String?, "google")
            }
        assertTrue(filter.include(owned))
        assertFalse(filter.include(unowned))
        assertFalse(filter.include(Table().setName("groupXrecords").setSchema(Schema(null as String?, "core"))))
        assertFalse(filter.include(Table().setName("GROUP.RECORDS").setSchema(Schema(null as String?, "core"))))
        listOf(owned to true, unowned to false).forEach { (table, expected) ->
            val objects =
                listOf(
                    Column().setName("name").setRelation(table),
                    Index().setName("by_name").setRelation(table),
                    PrimaryKey().setName("pk").setTable(table),
                    UniqueConstraint().setName("unique_name").setRelation(table),
                    ForeignKey().setName("fk").setForeignKeyTable(table).setPrimaryKeyTable(owned),
                )
            objects.forEach { child ->
                assertEquals(expected, filter.include(child), child.toString())
            }
        }
    }
}
