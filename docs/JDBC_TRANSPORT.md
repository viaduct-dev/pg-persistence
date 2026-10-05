# JDBC transport

JDBC executes pg_graphql directly over a database connection. HTTP remains supported,
including per-request headers and the existing HTTP retry/recovery
implementation. The optional `dev.viaduct.persistence:jdbc` artifact depends on the runtime,
not DBOS, Hibernate, a connection pool, or a particular JDBC driver.

## Configure a client

Use the same version of `jdbc` as `runtime` and add a PostgreSQL driver to the application.
Configure a `javax.sql.DataSource` using your application's connection pool and credentials.
The target database must have pg_graphql installed with `graphql.resolve` available.

```kotlin
val executor = JdbcPgGraphqlExecutor(dataSource)
val dbClient = DbClient(executor)
val mutations = PgGraphqlMutationClient(executor)
```

All existing explicit GRT input conversion, reads, and insert/update/delete APIs remain available.
`dbClient.transaction(ctx) { ... }` still buffers operations and executes one GraphQL document.
This constructor obtains a connection for each request, disables autocommit, executes the request,
and commits only a successfully decoded response without GraphQL errors. Failures roll back the
request. It closes the borrowed connection handle, returning it to the pool when one is used.
The application remains responsible for closing its pool at shutdown.

## Use a caller-owned transaction

Construct the executor with a connection whose autocommit is already disabled:

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

withContext(Dispatchers.IO) {
    dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val client = PgGraphqlMutationClient(JdbcPgGraphqlExecutor(connection))
            client.insert(groupEntity, groupInput.toPgGraphqlInsert())
            client.insert(membershipEntity, membershipInput.toPgGraphqlInsert())
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        }
    }
}
```

Here `groupInput` and `membershipInput` are application-provided generated Viaduct input GRTs;
`groupEntity` and `membershipEntity` are their `PgGraphqlEntity` table descriptions.
The example runs inside a suspend function. `Dispatchers.IO` provides threads for blocking I/O,
including acquiring a connection and waiting for JDBC calls to complete.

The executor closes statements and result sets, but never commits, rolls back, changes autocommit,
or closes this connection. The caller owns all transaction boundaries. Do not use the connection
concurrently or retain the executor after closing the connection. If another framework owns the
connection, let that framework perform commit and rollback instead of the example's manual calls.
Keep that connection on the framework's calling thread; do not add `withContext(Dispatchers.IO)`
inside a framework transaction callback.

For Java or synchronous framework callbacks, `executor.executeBlocking(request, headers)`
accepts the same `PgGraphqlRequest` and returns the same `DbResult` as `execute`, without
requiring a coroutine. It uses the same connection-ownership and error rules and stays on the
calling thread. Unlike the suspend method, it does not check coroutine cancellation.

Successful writes are not committed until the caller commits. Likewise, `DbTransaction.commit()`
executes a buffered request but cannot commit a caller-owned JDBC transaction. Mutation errors throw
`UpstreamGraphqlException`, including from APIs with a `Result` suffix in this mode: do not
swallow that exception and then commit earlier writes. Roll back the enclosing transaction.

## Request metadata and authorization

JDBC does not pass through an HTTP gateway. It does not inherit the gateway's JWT validation,
role selection, headers, or other request processing. Use appropriately restricted database
credentials and keep Viaduct checker execution in the application.

By default, nonempty headers are rejected rather than silently ignored. If the application needs
request metadata, provide trusted setup explicitly:

```kotlin
val executor = JdbcPgGraphqlExecutor(
    dataSource,
    JdbcRequestSetup { connection, headers ->
        connection.prepareStatement("SELECT set_config('application_name', ?, true)").use { statement ->
            statement.setString(1, headers.getValue("x-trace"))
            statement.execute()
        }
    },
)
val dbClient = DbClient(
    executor,
    requestHeaders = DbRequestHeaders { ctx -> mapOf("x-trace" to traceIdFor(ctx)) },
)
```

`traceIdFor(ctx)` is application code. Setup runs synchronously on the same transaction connection
before pg_graphql. Use bound values and transaction-local settings; do not leave session state
on pooled connections. In a caller-owned transaction, local settings last until the caller ends
that transaction. This callback must not commit, roll back, close the connection, or blindly turn
untrusted headers into database privileges. No HTTP authorization scheme is automatically translated.

## Errors, blocking, and recovery

- Both transports use the common GraphQL response decoder. Successful query fields and error
  paths, locations, and extensions are preserved.
- A JDBC request using a `DataSource` rolls back on GraphQL errors. Query data remains readable, but mutation
  data is discarded because its writes were rolled back.
- Malformed responses and SQL failures throw, including from methods with a `Result` suffix.
  A failure during rollback also throws instead of returning a `DbResult`.
- JDBC calls block the current thread. The executor does not switch dispatchers, so a
  framework-owned connection stays on its calling thread. Applications using a `DataSource`
  can use `withContext(Dispatchers.IO)` around their calls, as in the example above.
- Cancellation is checked before execution and before an owned commit. This does not interrupt
  an already-blocked JDBC statement; configure driver/database timeouts. Cancellation or connection
  loss during commit may leave the outcome unknown.
- There is no automatic JDBC retry, durable result table, or recovery workflow in this feature.
  Losing the connection during commit must not trigger a blind retry or fallback to HTTP.
  The existing HTTP retryable-transaction configuration remains a separate feature; this work
  does not add equivalent JDBC recovery or wire that configuration to a JDBC session.

## Direct GraphQL execution

`PgGraphqlExecutor.execute(PgGraphqlRequest(document, variables, operationName), headers)` returns
`DbResult<JsonObject>` for the entire GraphQL data object. An operation name is optional for
a document containing one operation and required when selecting among several operations.
JDBC validates that selection before acquiring an owned connection. Values are bound as parameters,
not interpolated into SQL.

## Verification

```sh
./gradlew :runtime:test :jdbc:test
```

The JDBC integration tests require pg_graphql in the isolated local `kan23_jdbc_tests` database.
The default URL is `jdbc:postgresql://127.0.0.1:55322/kan23_jdbc_tests`, with local test credentials
`postgres`/`postgres`. Override `PG_JDBC_TEST_URL` and `PG_JDBC_TEST_PASSWORD` as needed.
Tests create and remove only a uniquely named schema and fail rather than skip without the database.
Keep this database separate from the plugin integration database: concurrent schema changes there
can invalidate pg_graphql schema inspection even when tests use different table names.

Database tests cover the normal client APIs, transactions, parameter binding, connection ownership,
operation selection, and request setup. Controlled response and connection tests cover partial read
errors, malformed GraphQL responses, rollback/commit failures, and cancellation; they do not simulate a
full network partition or claim recovery from an uncertain commit.
