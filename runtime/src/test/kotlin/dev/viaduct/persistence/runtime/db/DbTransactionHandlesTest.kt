package dev.viaduct.persistence.runtime.db

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class DbTransactionHandlesTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `handles cannot select results from another transaction`(immediate: Boolean) =
        runBlocking {
            val first = commit(immediate)
            val second = commit(immediate)
            assertThat(first.result[second.value]).isNull()
            assertThat(second.result[first.value]).isNull()
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `stored results retain the original handle identity`(immediate: Boolean) =
        runBlocking {
            val committed = commit(immediate)
            val restored = DbTransactionResult.decode(committed.result.encode())
            assertThat(restored[committed.value]).isNotNull().isEqualTo(committed.result[committed.value])
            assertThat(restored[commit(immediate).value]).isNull()
        }

    private suspend fun commit(immediate: Boolean): DbTransactionCommit<DbTransactionOperation> {
        val block: DbTransactionScope.() -> DbTransactionOperation = {
            insert(PgGraphqlEntity("Group"), PgGraphqlObject.of("name" to "Chess"))
        }
        val data = """{"operation0":{"affectedCount":1,"records":[{"uuidId":"group-1"}]}}"""
        return if (immediate) {
            executeImmediateTransaction({ DbResult(Json.parseToJsonElement(data).jsonObject) }, block)
        } else {
            DbTransactionTestFixture("""{"data":$data}""").execute(block)
        }
    }
}
