package dev.viaduct.persistence.runtime.graphql

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PgGraphqlResponseTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"data":{"group":{"name":"Guest community"}}}""",
            """{"errors":[{"message":"duplicate key"}]}""",
            """{"data":{"group":{"name":null,"description":"Guest community"}},
                "errors":[{"message":"Cannot read name","path":["groups",0,"name"],
                "locations":[{"line":2,"column":3}],"extensions":{"code":"DENIED","details":[1,null]}}]}""",
        ],
    )
    fun `stored response preserves data and all supported error details`(response: String) {
        val decoded = decodePgGraphqlResponse(response)

        assertThat(decodePgGraphqlResponse(encodePgGraphqlResponse(decoded))).isEqualTo(decoded)
    }
}
