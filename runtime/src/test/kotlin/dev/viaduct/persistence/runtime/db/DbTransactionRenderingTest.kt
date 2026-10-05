package dev.viaduct.persistence.runtime.db

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

class DbTransactionRenderingTest {
    @Test
    fun `commit renders mixed operations as one GraphQL mutation`() =
        runBlocking {
            val fixture = DbTransactionTestFixture()
            fixture.addMixedOperations()

            fixture.commit()

            val expected = javaClass.getResource("/db-transaction-request.json")!!.readText()
            assertThat(fixture.requests().single()).isEqualTo(Json.parseToJsonElement(expected).jsonObject)
        }
}
