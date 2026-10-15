# Repository-owned failure handling

Application code, tests, examples, compatibility sources, and build scripts no longer use explicit
`Throwable`/`Error` catch clauses or Kotlin's `runCatching`, `mapCatching`, and `recoverCatching` helpers.
This cleanup is independent of the deferred-forwarding timeout investigation.

Production `captureException` helpers catch `Exception`, rethrow cancellation, and let JVM errors
escape. Test helpers capture ordinary exceptions, including expected cancellation; fatal-error
regressions use assertions for the specific expected error type.

Transaction lifecycle cleanup runs in `finally`. Immediate transactions record a failed-operation
flag even when application code consumes a fatal failure, preventing a later commit. Ordinary
exceptions retain their original identity and suppressed rollback/close errors across coroutine
boundaries through exception-only Result wrappers. Reflection propagates a target failure instead
of hiding a fatal error inside an `InvocationTargetException` fallback.

`verifyNoBroadFailureCatches` scans owned Java, Kotlin, and Groovy sources, including build scripts,
fixtures and nested example/compatibility sources. Normal `test`, `check`, and `build` tasks depend
on it. The same guard is installed in Gateloom's backend build. Comments and string literals are
excluded from the token scan. The guard rejects broad catch types and imports/usages of the broad
Result helpers, including aliases imported from those helpers. This is a source-policy check,
not a full JVM type checker.

The guard does not inspect third-party artifacts, external source checkouts used by the optional
Viaduct probe harness, or generated build output. Compiler-generated `finally`/resource-cleanup
handlers, Kotlin `use`, test assertion libraries, coroutines, and upstream Viaduct can still contain
`Throwable` handling in their bytecode. No dependency-wide absence of such handlers is claimed.

## Validation

The guard was exercised in an isolated Gradle project. It rejected multiline and fully qualified
fatal catch types plus a qualified broad Result helper, and accepted ordinary exception handling
while ignoring comments and string examples.

The standard library build runs this guard alongside formatting, Detekt, SpotBugs, runtime,
JDBC, Gradle consumer, and DBOS compatibility tests. Current verification is recorded in
[the tenant schema guide](../TENANT_DATABASE_SCHEMAS.md#verification) and
[PR #40](https://github.com/viaduct-dev/pg-persistence/pull/40). The schema branch is based on
#36; experimental native Hibernate runtime tests from #37 are not part of this branch.
