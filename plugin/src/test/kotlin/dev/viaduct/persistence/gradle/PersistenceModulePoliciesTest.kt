package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.model.PersistenceModelPolicy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersistenceModulePoliciesTest {
    @TempDir
    private lateinit var directory: File

    @Test
    fun `central policy includes semantic exclusions unique keys relationships and retries`() {
        val people =
            artifact(
                "people",
                """
                databaseSchema: people
                types:
                  Person:
                    semanticNotNull: true
                    unique: [[name]]
                    indexes: [[name]]
                    fields:
                      teams:
                        relationship:
                          inverseField: members
                  External:
                    excluded: true
                """.trimIndent(),
            )
        val orders =
            artifact(
                "orders",
                """
                databaseSchema: orders
                retryableTransactions: true
                types:
                  Team:
                    fields:
                      name:
                        semanticNotNull: true
                """.trimIndent(),
            )
        val merged = PersistenceModulePolicies.merge(PersistenceModelPolicy(), listOf(orders, people, people))
        assertEquals(setOf("External"), merged.deniedTypeNames)
        assertEquals(setOf("Person"), merged.semanticNotNullTypeNames)
        assertEquals(setOf("Team.name"), merged.semanticNotNullFieldCoordinates)
        assertEquals(mapOf("Person.teams" to "members"), merged.inverseFieldOverrides)
        assertEquals(mapOf("Person" to listOf(listOf("name"))), merged.uniqueKeysByType)
        assertEquals(mapOf("Person" to listOf(listOf("name"))), merged.indexesByType)
        assertEquals(true, merged.retryableTransactions)
        assertEquals(null, merged.databaseSchema)
        assertEquals(merged, PersistenceModulePolicies.merge(PersistenceModelPolicy(), listOf(people, orders)))
    }

    @Test
    fun `conflicting policies and inconsistent placement are rejected`() {
        val first = artifact("people", "databaseSchema: people\ntypes:\n  Person:\n    unique: [[name]]")
        val second = artifact("other", "databaseSchema: other\ntypes:\n  Person:\n    unique: [[email]]")
        assertFailsWith<IllegalArgumentException> {
            PersistenceModulePolicies.merge(PersistenceModelPolicy(), listOf(first, second))
        }
        val inconsistent = artifact("invalid", "databaseSchema: orders")
        assertFailsWith<IllegalArgumentException> {
            PersistenceModulePolicies.merge(PersistenceModelPolicy(), listOf(inconsistent))
        }
    }

    private fun artifact(
        module: String,
        yaml: String,
    ): File =
        directory.resolve(module).apply { check(mkdirs()) }.resolve(PersistencePlacementRegistrar.FILE_NAME).apply {
            writeText(
                buildJsonObject {
                    put("module", module)
                    put("databaseSchema", module)
                    put("policyYaml", yaml)
                }.toString(),
            )
        }
}
