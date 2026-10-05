package dev.viaduct.persistence.jdbc

import dev.viaduct.persistence.runtime.db.DbResult
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlException
import dev.viaduct.persistence.runtime.graphql.PgGraphqlExecutor
import dev.viaduct.persistence.runtime.graphql.PgGraphqlRequest
import dev.viaduct.persistence.runtime.graphql.decodePgGraphqlResponse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import javax.sql.DataSource

/** Trusted, synchronous setup for a JDBC request. No HTTP header is automatically treated as authorization. */
fun interface JdbcRequestSetup {
    fun configure(
        connection: Connection,
        headers: Map<String, String>,
    )
}

/**
 * Blocking pg_graphql execution on the calling thread. Choose owned or caller-managed connections explicitly.
 * The application supplies its PostgreSQL driver and owns the DataSource or borrowed Connection.
 */
class JdbcPgGraphqlExecutor private constructor(
    private val run: ((Connection) -> DbResult<JsonObject>) -> DbResult<JsonObject>,
    private val setup: JdbcRequestSetup,
    private val ownsConnection: Boolean,
    private val timeouts: JdbcTimeouts,
) : PgGraphqlExecutor {
    /** Each request borrows a fresh connection, commits on success, and rolls back on failure. */
    @JvmOverloads
    constructor(
        dataSource: DataSource,
        setup: JdbcRequestSetup = rejectHeaders,
        timeouts: JdbcTimeouts = JdbcTimeouts(),
    ) : this({ work ->
        GraphqlJdbcConnection.owned(dataSource, work, { it.errors.isEmpty() }, timeouts.networkMillis)
    }, setup, true, timeouts)

    /** Use only inside a caller-managed transaction. The caller must roll back when an operation throws. */
    @JvmOverloads
    constructor(
        connection: Connection,
        setup: JdbcRequestSetup = rejectHeaders,
        timeouts: JdbcTimeouts = JdbcTimeouts(),
    ) : this({ work ->
        check(!connection.autoCommit) { "Caller-owned JDBC connection requires an open transaction" }
        GraphqlJdbcConnection.borrowed(connection, work, timeouts.networkMillis)
    }, setup, false, timeouts)

    override suspend fun execute(
        request: PgGraphqlRequest,
        headers: Map<String, String>,
    ): DbResult<JsonObject> {
        val context = currentCoroutineContext()
        context.ensureActive()
        return executeRequest(request, headers) { context.ensureActive() }
    }

    /** Synchronous execution for transaction frameworks that require work on their calling thread. */
    fun executeBlocking(
        request: PgGraphqlRequest,
        headers: Map<String, String>,
    ): DbResult<JsonObject> = executeRequest(request, headers) {}

    private fun executeRequest(
        request: PgGraphqlRequest,
        headers: Map<String, String>,
        beforeReturn: () -> Unit,
    ): DbResult<JsonObject> {
        val mutation = request.isMutation
        val result =
            run { connection ->
                setup.configure(connection, headers)
                val response =
                    GraphqlJdbcConnection.resolve(
                        connection,
                        request.document,
                        request.variables.toString(),
                        request.operationName,
                        timeouts.querySeconds,
                    )
                val result = decodePgGraphqlResponse(response)
                beforeReturn()
                if (result.errors.isNotEmpty() && !ownsConnection && mutation) {
                    throw UpstreamGraphqlException(result.errors)
                }
                result
            }
        return if (mutation && result.errors.isNotEmpty()) DbResult(null, result.errors) else result
    }

    companion object {
        private val rejectHeaders =
            JdbcRequestSetup { _, headers ->
                require(headers.isEmpty()) {
                    "JDBC does not interpret HTTP headers; configure JdbcRequestSetup explicitly"
                }
            }
    }
}
