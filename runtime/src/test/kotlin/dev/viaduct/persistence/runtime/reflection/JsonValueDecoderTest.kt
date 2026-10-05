package dev.viaduct.persistence.runtime.reflection

import kotlinx.serialization.json.Json
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonValueDecoderTest {
    @Test
    fun `untyped JSON preserves strings and numeric precision`() {
        val decoded =
            JsonValueDecoder.decode(
                Json.parseToJsonElement(
                    """{"code":"00123","flag":"true","large":9007199254740993,"decimal":0.1234567890123456789}""",
                ),
                Any::class.java,
            )

        assertEquals(
            mapOf(
                "code" to "00123",
                "flag" to "true",
                "large" to 9007199254740993L,
                "decimal" to BigDecimal("0.1234567890123456789"),
            ),
            decoded,
        )
    }
}
