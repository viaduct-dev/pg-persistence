@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.connection

import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.DbRead
import dev.viaduct.persistence.runtime.db.DbRoot
import dev.viaduct.persistence.runtime.graphql.PgGraphqlExecutor
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Connection
import viaduct.api.types.MultidirectionalConnectionArguments
import viaduct.api.types.OffsetCursor
import viaduct.api.types.Query
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import viaduct.api.types.Object as ViaductObject

class ModernPagingApiTest {
    @ParameterizedTest
    @ValueSource(strings = ["first", "after", "last", "before", "offset"])
    fun `raw root arguments cannot provide another paging API`(name: String) {
        assertFailsWith<IllegalArgumentException> {
            PagingAccess.validateRoot(DbRoot("itemsCollection", "($name: \$value)"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["zero-first", "negative-last", "mixed", "native-after", "native-before", "overflow-after"])
    fun `viaduct rejects invalid arguments before database execution`(case: String) =
        runBlocking<Unit> {
            val paging =
                when (case) {
                    "zero-first" -> TestArguments(first = 0)
                    "negative-last" -> TestArguments(last = -1)
                    "mixed" -> TestArguments(first = 1, last = 1)
                    "native-after" -> TestArguments(after = "database-cursor")
                    "native-before" -> TestArguments(before = "database-cursor")
                    else -> TestArguments(after = OffsetCursor.fromOffset(Int.MAX_VALUE).value)
                }
            val resolver = mockk<ResolverExecutionContext<Query>>()
            val context =
                object :
                    ConnectionFieldExecutionContext<ViaductObject, Query, TestArguments, Connection<*, *>>,
                    ResolverExecutionContext<Query> by resolver {
                    override val arguments = paging

                    override suspend fun getObjectValue(): ViaductObject = error("Not needed")

                    override suspend fun getQueryValue(): Query = error("Not needed")
                }
            val client = DbClient(PgGraphqlExecutor { _, _ -> error("Database should not be called") })
            assertFailsWith<IllegalArgumentException> {
                client.fetchConnection(
                    context,
                    DbRead(DbRoot("itemsCollection")),
                    mockk<SelectionSet<Connection<*, *>>>(),
                )
            }
        }

    @Test
    fun `public database client has only the modern connection entry point`() {
        assertEquals(
            setOf("fetchConnection"),
            DbClient::class.java.methods
                .map { it.name.substringBefore('$') }
                .filter { it.contains("Connection") || it.startsWith("fetchUuid") }
                .toSet(),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ConnectionPageRequest",
            "NestedConnectionPageRequest",
            "UuidConnectionPage",
            "UuidConnectionEdge",
            "UuidConnectionPageInfo",
        ],
    )
    fun `parallel public paging types are removed`(name: String) {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("dev.viaduct.persistence.runtime.connection.$name")
        }
    }

    private data class TestArguments(
        override val first: Int? = null,
        override val after: String? = null,
        override val last: Int? = null,
        override val before: String? = null,
    ) : MultidirectionalConnectionArguments
}
