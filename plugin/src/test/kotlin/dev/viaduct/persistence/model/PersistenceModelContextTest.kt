package dev.viaduct.persistence.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersistenceModelContextTest {
    @Test
    fun `generated entity results are ordered snapshots`() {
        val context = PersistenceModelContext(emptyMap())
        val first = PersistenceEntity("First", true, emptyList())
        val second = PersistenceEntity("Second", true, emptyList())
        context.register(first)
        val snapshot = context.generatedEntities

        context.register(second)

        assertEquals(listOf(first), snapshot)
        assertEquals(listOf(first, second), context.generatedEntities)
    }

    @Test
    fun `registering an enum preserves the first definition`() {
        val context = PersistenceModelContext(emptyMap())
        val original = PersistenceEnum("Status", listOf("ACTIVE"))
        context.register(original)

        context.register(PersistenceEnum("Status", listOf("INACTIVE")))

        assertEquals(listOf(original), context.generatedEnums)
    }

    @Test
    fun `generated collections cannot be changed outside the context`() {
        val context = PersistenceModelContext(emptyMap())
        context.register(PersistenceEntity("First", true, emptyList()))
        context.register(PersistenceEnum("Status", listOf("ACTIVE")))

        assertFailsWith<UnsupportedOperationException> { (context.generatedEntities as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (context.generatedEnums as MutableList).clear() }
    }
}
