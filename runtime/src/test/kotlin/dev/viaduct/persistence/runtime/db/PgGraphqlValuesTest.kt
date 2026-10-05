package dev.viaduct.persistence.runtime.db

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PgGraphqlValuesTest {
    @Test
    fun `allOf preserves conditions that use the same field`() {
        val filter =
            PgGraphqlFilter
                .allOf(
                    PgGraphqlFilter.eq("ownerId", "trusted-owner"),
                    PgGraphqlFilter.eq("ownerId", "different-owner"),
                ).encoded()

        assertEquals(
            Json.parseToJsonElement(
                """{"and":[{"ownerId":{"eq":"trusted-owner"}},{"ownerId":{"eq":"different-owner"}}]}""",
            ),
            filter,
        )
    }
}
