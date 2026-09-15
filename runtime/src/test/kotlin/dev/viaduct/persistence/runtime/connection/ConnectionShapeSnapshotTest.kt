@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.connection

import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConnectionShapeSnapshotTest {
    @Test
    fun `edge fields snapshot caller collections`() {
        val field = mockk<StoredEdgeResponseField>()
        val customFields = mutableListOf(field)
        val edge = EdgeShape(mockk(), mockk(), null, customFields)
        customFields.clear()
        assertEquals(listOf(field), edge.customFields)
    }

    @Test
    fun `edge copy snapshots replacement fields`() {
        val original = EdgeShape(mockk(), mockk(), null)
        val field = mockk<StoredEdgeResponseField>()
        val fields = mutableListOf(field)
        val copy = original.copy(customFields = fields)
        fields.clear()
        assertEquals(listOf(copy.node, field), copy.fields)
    }

    @Test
    fun `edge fields cannot be changed through getter`() {
        val edge = EdgeShape(mockk(), mockk(), null, listOf(mockk()))
        assertFailsWith<UnsupportedOperationException> { (edge.customFields as MutableList).clear() }
    }

    @Test
    fun `connection snapshots requested field names`() {
        val fields = mutableSetOf("edges")
        val connection = connection(fields)
        fields.clear()
        assertEquals(setOf("edges"), connection.requestedFieldNames)
    }

    @Test
    fun `connection copy snapshots replacement selections`() {
        val fields = mutableSetOf("nodes")
        val copy = connection(setOf("edges")).copy(requestedFieldNames = fields)
        fields.clear()
        assertEquals(setOf("nodes"), copy.requestedFieldNames)
    }

    @Test
    fun `connection selections cannot be changed through getter`() {
        val connection = connection(setOf("edges"))
        assertFailsWith<UnsupportedOperationException> {
            (connection.requestedFieldNames as MutableSet).clear()
        }
    }

    private fun connection(fields: Set<String>) =
        ConnectionShape(
            type = mockk(),
            edgeField = mockk(),
            edge = EdgeShape(mockk(), mockk(), null),
            nodesField = null,
            pageInfoField = null,
            pageInfo = null,
            requestedFieldNames = fields,
        )
}
