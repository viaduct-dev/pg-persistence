package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.orm.grt.isGrtFieldSet
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GrtPublicAccessTest {
    @Test
    fun `an explicit null is a supplied field`() {
        assertEquals(true, isGrtFieldSet { null })
    }

    @Test
    fun `ordinary getter failures are not mistaken for omission`() {
        assertFailsWith<IllegalStateException> { isGrtFieldSet { error("policy failure") } }
    }

    @Test
    fun `getter cancellation propagates`() {
        assertFailsWith<CancellationException> { isGrtFieldSet { throw CancellationException("cancelled") } }
    }

    @Test
    fun `fatal getter failures propagate`() {
        assertFailsWith<LinkageError> { isGrtFieldSet { throw LinkageError("fatal") } }
    }
}
