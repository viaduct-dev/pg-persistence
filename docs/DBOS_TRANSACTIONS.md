# DBOS transactions

DBOS is an optional implementation of the existing `DbClient.transaction` API. Configure it
once; application code keeps the same transaction lambda and explicit GRT input conversion.
The default implementation remains buffered, including when using plain JDBC.

## Application setup

Add `dev.viaduct.persistence:dbos` at the same version as `runtime`, plus a PostgreSQL JDBC driver.
It includes the shared JDBC transport, `dev.dbos:transact:1.0.0`, and DBOS's JDBI transaction
implementation. JDBI manages JDBC connections internally; applications still supply a `DataSource`
and use the same API below. The runtime and plain JDBC modules do not depend on it. DBOS brings Kotlin stdlib
2.4.0, so Kotlin applications using it need a compatible compiler. Other applications do not.

```kotlin
val dbClient = DbClient(
    executor = JdbcPgGraphqlExecutor(dataSource),
    transactions = DbosTransactions(dbos, dataSource),
)
```

The application owns the connection pool and DBOS lifecycle. Register workflows before launching
DBOS. The library constructs its transaction step factory from this DBOS configuration, including its
configured serializer. The factory uses the application database with pg_graphql installed and manages DBOS's
transaction-result table. The pg-persistence plugin does not install additional transaction SQL
for DBOS. Reads and ordinary mutations outside `transaction` still use the configured executor;
they do not automatically join its DBOS transaction.

The `DataSource` must lend connections that this operation owns exclusively, not a connection
participating in another application's transaction. Pools may default autocommit to off: the adapter
rolls back any existing work on each newly borrowed connection and enables autocommit before JDBI
begins its own transaction. The pool remains responsible for resetting connection settings on return.

## Several calls in one database transaction

Inside application code invoked by a registered DBOS workflow:

```kotlin
val input = ctx.arguments.input
val committed = dbClient.transaction(ctx) {
    entity<Group>().insert(input.group.toPgGraphqlInsert())
    entity<GroupMember>().insert(input.membership.toPgGraphqlInsert())
}
val membership = committed.result[committed.value]
```

Here the application's input has `group` and `membership` inputs accepted by those pg_graphql
tables. The membership identifies the group through its foreign-key ID. The example returns the
last operation's handle, just as with the buffered API. No request encoding or connection setup
is needed around individual transactions.

| Implementation | When mutations execute | Who commits |
| --- | --- | --- |
| Default buffered transaction | After the lambda, in one pg_graphql mutation | HTTP/JDBC transport, subject to JDBC connection ownership |
| DBOS transaction | Each call executes immediately through pg_graphql on one JDBC connection | DBOS commits writes and its saved result together after the lambda succeeds |

DBOS's `JdbiStepFactory.inStep` delegates begin, commit, rollback, and close to JDBI. A failure before
commit rolls back the block's writes. Errors during commit or cleanup can occur after the writes
committed; the adapter checks the saved result before reporting failure or retrying.
Batch insert is one insert containing all inputs; batch update/delete execute
one operation per input. Existing handles still identify results after completion; immediate
execution does not add intermediate-result reads to the shared API.

DBOS uses a callback, not a transaction handle that may outlive a workflow step. Standalone
`beginTransaction` and explicit `commit()` / `abort()` calls are unavailable with DBOS configured;
the client directs callers to `transaction(ctx) { ... }`. The HTTP `operationId` overload,
prepare/resume/lookup, and retry configuration remain separate from DBOS recovery. They must not
silently change meaning or fall back to buffered execution.

## Workflow execution and recovery

The application must register a workflow with stable, serializable input data. It must invoke
the transaction on the DBOS workflow thread. A synchronous Kotlin workflow can call a suspend
application method using a same-thread coroutine bridge:

```kotlin
@Workflow
override fun changeMembership(input: MembershipChange): Boolean = runBlocking {
    membershipService.changeMembership(input) // Uses the configured dbClient.transaction API.
    true
}
```

`MembershipChange` and `membershipService` are application-defined. Save ordinary authorized
input data, not `ctx` or request-bound GRTs as workflow arguments. The service must reconstruct
any execution context it needs during recovery; capturing a resolver lambda does not make it
recoverable. A resolver starting a workflow may switch to `Dispatchers.IO` first, then set
`WorkflowOptions(workflowId)` and invoke the registered proxy on that thread. Do not switch
dispatchers inside the DBOS transaction.

DBOS identifies transactions by workflow ID and step sequence. Supply `StepFactoryOptions`
when constructing `DbosTransactions` if a specific step name or isolation level is needed.
Replay restores both the lambda's value and the mutation results without running the block.
The value must be saved and restored by DBOS's configured serializer, which defaults to Jackson;
implementing Java `Serializable` alone is not sufficient. The library validates restoration with
the same serializer and format selection that DBOS uses to store and recover its result,
including decoding the saved operation results before committing.
Kotlin `Unit`, null, and operation handles are supported. Contexts, clients, and GRTs should not
be returned as the block value. Serialization happens before commit, so unsupported values
roll back writes.

Operation handles include a transaction identity, preserved in saved results. Using another
transaction's handle returns null instead of selecting a same-numbered operation accidentally.

The block can run again after a retryable database failure before commit. Keep external side
effects out of it and retain compatible workflow code/input versions for recovery. Empty and
nested transactions are rejected.

## Errors and authorization

Each mutation uses the shared `JdbcPgGraphqlExecutor` on DBOS's connection. The runtime validates
its payload before accepting it. GraphQL errors become a serializable `DbosGraphqlException`
retaining paths, locations, and extensions. A mutation failure marks the transaction failed even
if application code catches the exception: later mutations cannot continue, and earlier writes
cannot commit. Application exceptions also roll back. The transaction object cannot be used after
its block finishes or from another thread.

JDBC does not inherit HTTP gateway authentication. Use restricted credentials or supply an
explicit `JdbcRequestSetup` with `DbosTransactions(dbos, dataSource, options, setup)` to apply verified
request metadata on the transaction connection. Nonempty headers are rejected by default.
Do not derive privileges from unchecked headers. Viaduct checker execution, background
authorization, and permission to retrieve saved workflow results remain application responsibilities.

The adapter retries JDBC SQLSTATE `40001` and `40P01`. However, pg_graphql's public `graphql.resolve`
function removes SQLSTATE when converting database failures to GraphQL errors. The integration
allows at most three full transaction attempts for GraphQL failures, after each failed attempt
has rolled back. This also retries permanent GraphQL errors before returning their final failure.
It never classifies message text or fabricates SQLSTATE codes. Application exceptions are not
retried by this policy. Keep external effects out of the block; even code before the failed
mutation runs again.

Both kinds of retry use the same increasing delay, starting at 1 ms and capped at 2 seconds.
Each wait has a small random variation so competing transactions do not all retry together.
Thread interruption stops the wait and prevents another attempt. Every failed attempt is checked
for a saved result before retrying or reporting failure. Connection failures are not blindly retried.
If rollback, close, or recording the transaction error also fails, those failures are retained
without replacing the original transaction error.

Both GraphQL attempts and JDBC serialization/deadlock retries share a 30-second retry deadline.
Change it at client setup when needed:

```kotlin
val transactions = DbosTransactions(dbos, dataSource, java.time.Duration.ofSeconds(10))
```

The overload accepting `options` and `setup` also accepts the duration as its final argument.
It must be positive and fit in nanoseconds. Once the deadline expires, no further transaction
attempt starts and `DbosTransactionTimeoutException` is returned. The last failure is retained
as a suppressed exception. Already-running JDBC calls and application code are not interrupted;
the retry wait can also delay observing expiry. Configure connection-pool, JDBC, and database
timeouts separately. A successful attempt can finish after this deadline: it bounds retries,
not the duration of a database transaction.

After any failed attempt, the integration checks the saved transaction result through DBOS's
step-factory API. This covers cleanup errors after commit, including errors without SQLSTATE,
and a duplicate worker failing on a business constraint after another worker committed the same
step. A readable saved result is returned without executing the writes again. Otherwise, the
original failure follows the retry policy above, with any lookup failure suppressed. A saved
error or decoding error encountered while restoring the result propagates instead of being
hidden by the attempt's failure. Failure to find or restore a result does not prove rollback.
A sustained database outage can still leave the outcome uncertain. Do not retry with a new
workflow ID or switch to HTTP blindly.

If application code fails *after* a successfully recorded transaction, use DBOS's workflow
forking API at the step after that transaction. For a workflow whose first step is the transaction:

```kotlin
val recovered = dbos.forkWorkflow<String, RuntimeException>(workflowId, 1).result
```

The return type here is the example workflow's String result. The fork copies the completed
step's output, so the transaction block and writes do not run again. Select the boundary from
the workflow definition, not by guessing; restarting before a committed transaction can repeat
writes. DBOS 1.0's `resumeWorkflow` does not restart workflows already in ERROR. Saved transaction
errors remain errors, and neither API undoes committed work. Tests use these APIs, not manual
deletion of DBOS workflow records.

An already-cancelled coroutine cannot start a transaction. The client checks again after obtaining
request headers, before passing work to DBOS. This does not cancel an independently running durable
workflow or interrupt a JDBC call already in progress.

Cancellation is not proof of rollback: blocking JDBC work can finish before cancellation is
observed, and nothing here undoes a commit. Configure database/driver timeouts. External service
calls are not rolled back by PostgreSQL, and separate DBOS steps are separate transactions.

## Verification

```sh
./gradlew check
```

`:dbos:check` resolves project and test dependencies together and passes that complete classpath,
along with the current project JARs, to the DBOS integration tests compiled separately with Kotlin
2.4. The nested build does not resolve a second set of test libraries; a regression test checks
for duplicate Kotlin and coroutine classes. No local Maven repository, published
snapshot, or included build is required. Run these tests through the parent build with
`./gradlew :dbos:kotlinCompatibilityTest`. They exercise the actual `DbClient.transaction` API,
conversion, immediate execution, rollback, handles, and saved-result replay. Input and node
fixtures have the same accessors as generated GRTs; this is not a full generated Viaduct server.
Additional tests inject connection setup, rollback, close, and error-recording failures and verify
connection release. Infrastructure tests also:

- Reuse one physical HikariCP connection after commit and rollback, with autocommit enabled and
  disabled. Verify isolation and autocommit are restored, read-only stays unchanged, and no connection stays borrowed.
- Kill a worker JVM before commit and after commit but before DBOS checkpoints the step. Restart
  it and verify uncommitted work runs again, while committed work is restored without repeating the block.
- Drop a real PostgreSQL COMMIT response using a local TCP proxy. Verify JDBC's connection error
  recovers the saved result without executing the block again.

Tests require pg_graphql in the isolated local `kan22_dbos_tests` database. The default URL is
`jdbc:postgresql://127.0.0.1:55322/kan22_dbos_tests`, with local `postgres` credentials. Override
`PG_DBOS_JDBC_URL` and `PG_DBOS_PASSWORD` for that isolated database. Tests create and remove
only uniquely named schemas and fail rather than skip without the database. Keep it separate
from plugin and JDBC test databases. The injected failures and single-connection TCP interruption
do not simulate a sustained database-wide outage. The concurrent recovery
test uses two JVMs and holds both transactions after their initial saved-result checks. It lets
one commit, then verifies that the other returns the same result despite its duplicate insert.
