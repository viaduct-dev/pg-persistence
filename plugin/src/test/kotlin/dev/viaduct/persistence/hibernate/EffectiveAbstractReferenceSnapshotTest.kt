package dev.viaduct.persistence.hibernate

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EffectiveAbstractReferenceSnapshotTest {
    @Test
    fun `reference snapshots caller columns`() {
        val columns = linkedMapOf("person_id" to "subjectPersonId")
        val reference = reference(columns)
        columns.clear()
        assertEquals(mapOf("person_id" to "subjectPersonId"), reference.columns)
    }

    @Test
    fun `copy snapshots replacement columns`() {
        val columns = linkedMapOf("group_id" to "subjectGroupId")
        val copied = reference(emptyMap()).copy(columns = columns)
        columns.clear()
        assertEquals(mapOf("group_id" to "subjectGroupId"), copied.columns)
    }

    @Test
    fun `columns cannot be changed through getter`() {
        val reference = reference(mapOf("person_id" to "subjectPersonId"))
        assertFailsWith<UnsupportedOperationException> { (reference.columns as MutableMap).clear() }
    }

    private fun reference(columns: Map<String, String>): EffectiveAbstractReference =
        EffectiveAbstractReference("public", "activity", "subject", true, columns)
}
