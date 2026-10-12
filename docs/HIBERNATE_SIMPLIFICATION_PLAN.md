# Simplify the GRT/Hibernate bridge

Keep context-aware hydration: Hibernate writes directly into a public GRT builder and publishes
the concrete GRT once. Remove persistence operations that Hibernate already implements before
changing that representation. This follows the native GlobalID work in PR #37.

## 1. Use native Hibernate operations

- Remove the delegate client's insert/find/update/delete and transaction facade.
- Configure context-aware instantiation through a per-session public Hibernate Interceptor.
  Return the actual Hibernate Session; remove the custom Session wrapper and client ownership token.
- Use Session.persist/find/remove and dirty checking for writes. Keep only GRT construction,
  validated replacement, and detached-result construction at the bridge boundary.
- Use native Hibernate transactions. The standalone Session.fromTransaction example includes a
  rollback guard for JVM Errors; its need was reproduced during this migration. Applications own
  session creation, trusted database identity, timeouts, and coroutine IO/cancellation integration.
- Keep selection-aware reads as a small adapter that accepts the caller's native Session. It
  must not create or commit a transaction. Existing entity execution checks remain in place.

Start here. Tests may keep a test-only transaction harness to exercise synchronous native APIs
from suspend resolvers; it must call Hibernate's transaction implementation rather than duplicate it.

## 2. Prove shared property access

Prototype Hibernate's public PropertyAccessStrategy with cached typed getter/setter callbacks.
Hydration still populates the GRT builder directly. Prove scalar hydration and dirty checking
against real generated GRTs and PostgreSQL before removing generated bean accessors.

Do not add a second scalar model, JSON protocol, request registry, thread-local, or private
Hibernate/Viaduct implementation access. Public GRT getter/builder invocation can be resolved
once at startup if necessary; measure invocation costs rather than assuming they are negligible.
Associations and collections continue to belong to Hibernate.

Only adopt the shared adapter if it removes generated code without replacing it with a similarly
large runtime mapper. A separate Hibernate representation followed by GRT conversion is a fallback,
not the starting design.

## 3. Preserve selection and schema behavior

Keep native Criteria/HQL tuple projections for narrow selections. Entity graphs are not a
guarantee that omitted basic columns stay unloaded. Preserve owned fields, per-context batch
selections, finite ordinary-object trees, aliases, modern Viaduct connections, and detached values.

Keep UUID database columns, schema-generation mappings, constraints, migrations, and native
GlobalID/cache behavior. Gateloom and batteries-included remain unchanged.

## Verification and completion

- Exercise native persist/find/remove, dirty checking, refresh, generated IDs, and GRT snapshots.
- Check concurrent context isolation, request mismatch rejection at projection, cancellation,
  fatal failures, rollback, initializer failures, and bounded lock contention using native transactions.
- Run affected PostgreSQL, real Viaduct engine, scalar, collection, cache, generator, and consumer tests.
- Run formatting, Detekt, and SpotBugs without new complexity suppressions.
- Compare generated source size, entity loads, allocations, and latency with the recorded baseline.
  Preserve exact connection ordering and duplicate collection members in comparisons.
- Document the supported native API, limitations, and results. Keep implementation/documentation
  and tests in separate commits when publishing to PR #37.

## Progress

The native API migration is implemented. The 335-line DelegateHibernateClient is replaced by
the 170-line GrtReads adapter; custom CRUD methods, transaction/session ownership plumbing, and
the TooManyFunctions suppression are removed. Native sessions use bindings.interceptor(ctx).
The test harness exercises native persistence and transactions; it is not a production API.

The shared public-property-access experiment passes against generated GRTs and PostgreSQL.
For one String field, Hibernate hydrates directly through the public builder and performs dirty
checking without generated scalar bean methods. Tests verify immutable old snapshots, unchanged
schema, unchanged flushes without UPDATEs, and narrow detached SQL reads. Method handles are
resolved at bootstrap; the class cache contains only immutable invocation metadata.

The migration exposed a Hibernate 7.3 native-helper failure mode: plain Session.fromTransaction
can commit a flushed write when its callback throws a JVM Error. The standalone guide and test
harness guard inside the callback: roll back every failure, retain the original throwable, and
check cancellation before returning to commit. Fatal failure, cancellation, and initializer-error
regressions pass. Coroutine debug recovery can copy an AssertionError while retaining the original
as its cause; the initializer test verifies both the failure and committed database state.

All 521 plugin tests pass with zero failures, errors, or skips. Hibernate/plugin formatting,
Detekt, and main/test SpotBugs checks pass. Both local performance runs also
pass exact result comparison. The one-field prototype shows similar latency and about 1% more
entity-hydration allocation. It remains test-only; production scalar accessors are still generated.
See [the performance comparison](HIBERNATE_GRT_DELEGATE_PERFORMANCE.md) for measured values and scope.

Before adopting shared property access, cover nullable/primitive/enum fields, scalar and JSON/Time
arrays, special getter names, refresh/omission, associations, and serialized-cache compatibility.
Measure net generated plus handwritten source size on richer schemas and repeat performance
comparisons. Do not replace the production generator until that broader adapter is demonstrably
simpler. Existing native association/collection and selective-read coverage already passes.

Verification logs are /private/tmp/grt-native-final-verification.log (521 passing tests and the
prototype performance case; its line-length finding was then corrected) and
/private/tmp/grt-native-final-quality.log (final static checks). Reproduce the local comparison:

```sh
./gradlew :plugin:delegatePerformanceTest --console=plain
GRT_BUILDER_ACCESS_PROTOTYPE=true ./gradlew :plugin:delegatePerformanceTest --console=plain
```

Save build/reports/hibernate-delegates/performance.md between runs; the task overwrites it.
