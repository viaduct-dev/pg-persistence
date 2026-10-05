package dev.viaduct.persistence.runtime.db

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DbTransactionTest {
    @Test
    fun `operations remain buffered until commit`() =
        runBlocking {
            val fixture = DbTransactionTestFixture()
            fixture.addMixedOperations()

            assertThat(fixture.requests()).isEmpty()
            fixture.commit()
            assertThat(fixture.requests()).hasSize(1)
        }

    @Test
    fun `transaction block commits after returning`() =
        runBlocking {
            val fixture = DbTransactionTestFixture()

            val committed =
                fixture.execute {
                    fixture.entity("Group").insert(PgGraphqlObject.of("name" to "Chess"))
                }

            assertThat(fixture.requests()).hasSize(1)
            assertThat(committed.result[committed.value]?.recordIds()).isEqualTo(listOf("group-1"))
        }

    @Test
    fun `transaction block aborts when it throws`() =
        runBlocking {
            val fixture = DbTransactionTestFixture()

            assertFailsWith<IllegalArgumentException> {
                fixture.execute {
                    fixture.entity("Group").insert(PgGraphqlObject.of("name" to "Chess"))
                    throw IllegalArgumentException("invalid group")
                }
            }

            assertThat(fixture.requests()).isEmpty()
        }

    @Test
    fun `concurrent additions receive distinct operation handles`() =
        runBlocking {
            val fixture = DbTransactionTestFixture()

            val handles =
                (1..100)
                    .map {
                        async(Dispatchers.Default) {
                            fixture.entity("Group").insert(PgGraphqlObject.of("name" to "Group $it"))
                        }
                    }.awaitAll()

            assertThat(handles.map(DbTransactionOperation::alias).toSet()).hasSize(100)
            fixture.abort()
        }

    @Test
    fun `update limit must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            PgGraphqlUpdate(
                values = PgGraphqlObject.of("name" to "Chess"),
                filter = PgGraphqlFilter.empty(),
                atMost = 0,
            )
        }
    }

    @Test
    fun `commit returns payloads by operation handle`() =
        runBlocking {
            val fixture = DbTransactionTestFixture()
            val handles = fixture.addMixedOperations()

            val result = fixture.commit()

            assertThat(handles.map { result[it]?.recordIds() })
                .isEqualTo(listOf(listOf("group-1"), listOf("member-1")))
        }

    @Test
    fun `abort discards operations and closes the transaction`() {
        val fixture = DbTransactionTestFixture()
        fixture.entity("Group").insert(PgGraphqlObject.of("name" to "Chess"))

        fixture.abort()
        fixture.abort()

        assertThat(fixture.requests()).isEmpty()
        assertFailsWith<IllegalStateException> {
            fixture.entity("Group").delete(PgGraphqlDelete(PgGraphqlFilter.empty()))
        }
    }

    @Test
    fun `empty commit fails without a request`() =
        runBlocking {
            val fixture = DbTransactionTestFixture()

            assertFailsWith<IllegalArgumentException> { fixture.commit() }

            assertThat(fixture.requests()).isEmpty()
        }

    @Test
    fun `committed transaction cannot be committed again`() =
        runBlocking {
            val fixture = DbTransactionTestFixture()
            fixture.entity("Group").insert(PgGraphqlObject.of("name" to "Chess"))
            fixture.commit()

            assertFailsWith<IllegalStateException> { fixture.commit() }
            assertThat(fixture.requests()).hasSize(1)
        }

    @Test
    fun `result form preserves partial data and errors`() =
        runBlocking {
            val fixture = DbTransactionTestFixture(DbTransactionTestFixture.PARTIAL_RESPONSE)
            val first = fixture.entity("Group").insert(PgGraphqlObject.of("name" to "Chess"))
            val second = fixture.entity("Group").insert(PgGraphqlObject.of("name" to "Go"))

            val result = fixture.commitResult()

            assertThat(
                PartialResult(
                    result.data?.get(first)?.recordIds(),
                    result.data?.get(second)?.recordIds(),
                    result.errors.map(UpstreamGraphqlError::message),
                ),
            ).isEqualTo(PartialResult(listOf("group-1"), null, listOf("member failed")))
        }

    private data class PartialResult(
        val firstRecordIds: List<String>?,
        val secondRecordIds: List<String>?,
        val errors: List<String>,
    )
}
