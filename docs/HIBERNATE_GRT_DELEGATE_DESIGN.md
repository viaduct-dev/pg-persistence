# GRT-first Hibernate persistence: delegate design and implementation plan

## Status and scope

The opt-in generated runtime now uses only public Viaduct getters, builders, resolver contexts, and selections. The earlier internal backing-data experiment is rejected and superseded. The public implementation is included in [PR #37](https://github.com/viaduct-dev/pg-persistence/pull/37) on branch `feat/hibernate-grt-runtime`. The expanded schema and large-collection regressions pass for the supported shapes: 223 selected cases passed, including 43 generated-runtime cases. The separately recorded performance comparison also passed. A Maven snapshot containing these follow-up changes has not been published. Existing providers remain available; Gateloom and batteries-included are unchanged.

The application-facing unit is an unchanged, concrete Viaduct object GRT. The delegate base supports ordinary objects and edge GRTs; Node lookup and GlobalID handling remain specialized. Pure association rows stay native Hibernate bookkeeping without invented GraphQL types. `DelegateHibernateClient` accepts and returns GRTs, while generated Hibernate delegates keep native association and collection bookkeeping. Hibernate owns queries, SQL, identity, dirty checking, associations, and transactions.

The delegate path does not use pg_graphql's execution protocol, selection planners, filter language, or JSON request/response translation. Compatibility with that provider's internal API is not required. The existing provider is retained as a separate approach.

The reverse-collection column and nullability defect was an existing main-branch bug, independent of this design. Its fix and regressions were verified and merged separately in [PR #38](https://github.com/viaduct-dev/pg-persistence/pull/38).

## Relationship identity and ordinary collection verification — 2026-10-09

Generated concrete relationships now require an identity whenever a non-null target GRT is supplied. A missing target identity previously became null and could silently clear a nullable relationship. PostgreSQL regressions reproduced that loss for both Nodes and ordinary objects before the fix. Both now reject the replacement before changing managed state; the tests also reload the committed row to verify the existing relationship remains intact. Explicit null remains valid for nullable relationships.

Ordinary-object collections are covered through real generated GRTs/delegates and PostgreSQL. Selected child fields detach before the session closes, native bags remain uninitialized, and finite parent/child selections terminate through cyclic relationships. The real Viaduct GraphQL engine returns the expected nested collection and sibling data.

All 69 selected generated-runtime, generator, and consumer-build cases were verified across the final runs, with no remaining test or static-analysis failure. The 69-case run passed 68 cases; the remaining failure was a new test assertion's inferred Kotlin array type, corrected with an explicit heterogeneous list type. The final rerun passed all 28 Node integration cases. All 18 object/edge cases, four engine cases, three large-collection cases, nine scalar/schema cases, six generator cases, and the consumer-build case passed. Formatting, Detekt, and Hibernate/plugin main/test SpotBugs checks passed. Each new test has one assertion.

Logs: `/private/tmp/grt-relationship-identity-baseline.log` (both bug reproducers fail against the prior implementation), `/private/tmp/grt-relationship-identity-final.log` (69-case run), and `/private/tmp/grt-relationship-identity-corrected.log` (28 passing Node integration cases and final test quality checks). The earlier object/edge and performance results below retain their original scope.

## Object and edge delegate results — 2026-10-09

The delegate base now supports concrete object GRTs. Node identities use a separate GlobalID binding; ordinary mapped objects use their existing UUID identity. Interface/union Node relationships retain concrete target GRTs, persisted edges contain their actual edge GRT, and pure association rows remain native Hibernate storage.

All 15 expanded PostgreSQL object/edge cases passed with zero failures, errors, or skips. They cover concrete abstract targets and replacement/nullability, mixed and single-member abstract lists without child hydration, ordinary field names, non-Node identity and finite cyclic selections, detached nested objects, selected custom edge fields, abstract edge pages/cursors, dirty checking and stable snapshots, one edge GRT shared by independent association mappings, physical-schema equivalence, and real GraphQL execution with field checkers, non-null propagation, and sibling data.

A preceding 63-case run passed the existing generated integration, engine, large-collection, scalar/schema, generator, and first 14 object cases. The final 15-case run recompiled the expanded fixture after adding the shared-edge regression. Formatting, Detekt, and Hibernate/plugin main/test SpotBugs checks passed. The earlier broader run had one obsolete test-adapter failure: its reflection call used the old selected signature. The adapter now calls the public typed delegate API; its regression passed in the 63-case run.

Automatic model discovery still selects Nodes, and abstract persistent targets must still be Nodes under the shared validator. Standalone non-Node mappings must already be explicitly included in the model with a schema ID backed by the existing UUID primary key. No identities or GraphQL types are invented for storage rows. These changes do not relax existing provider validation or modify consumer applications. Local performance measurements and the runtime suite recorded below were not freshly rerun for this object/edge follow-up.

Final verification command, with the existing local PostgreSQL test environment:

```sh
./gradlew :plugin:test --tests '*GeneratedDelegateObjectTest' \
  :hibernate:ktlintCheck :plugin:ktlintCheck :hibernate:detekt :plugin:detekt \
  :hibernate:spotbugsMain :plugin:spotbugsMain :plugin:spotbugsTest \
  --no-parallel --continue
```

Logs: `/private/tmp/grt-object-quality-final.log` (63 passing cases and static checks) and `/private/tmp/grt-object-latest-final.log` (all 15 expanded object/edge cases and final static checks).

## Generated-runtime results — 2026-10-09

The optional generated runtime uses public Viaduct APIs and is documented in PR #37. **223 selected plugin/native integration cases passed, including 43 generated-runtime cases, with zero failures, errors, or skips.** The Time/JSON-array follow-up expands schema coverage to nine cases; all 15 final targeted cases passed, including those nine and six generator cases. Formatting, Detekt, and Hibernate/plugin main/test SpotBugs checks passed with zero findings, errors, or missing classes. The separate performance comparison passed before this scalar follow-up. The existing runtime suite recorded 366 cases, 363 passed and 3 skipped; that earlier cached result was reused, not a fresh execution for this follow-up.

The generated-runtime cases compile real unchanged Viaduct GRT bytecode and the emitted typed delegates, bootstrap the normal generated HBM, apply generated PostgreSQL schema SQL, and execute against local PostgreSQL. They cover CRUD and generated IDs, scalar/enum/timestamp values, dirty checking, stable old snapshots and refresh, omission and invalid replacement rejection, owning associations and paired IDs, FK/join collections, synthesized collection owners, selective/batch results, missing nodes, and session ownership.

Modern connection tests cover forward/backward/default/empty pages, invalid arguments, rejected pre-paged queries, and already-present Hibernate proxies. Real-engine tests cover aliases, nested references, checker denial, indexed error paths, missing nodes, and non-null behavior. Failure tests cover cancellation/fatal rollback, concurrent request isolation, transaction-local session initialization, and contended lock timeout recovery.

New regressions ensure omitted nullable fields cannot silently clear persisted values, conflicting paired IDs (including explicit null) fail before managed replacement, and another client or request cannot reuse a transaction session. Public getter tests preserve explicit null and propagate ordinary failures, cancellation, and fatal errors. The generator architecture test rejects emitted references to Viaduct implementation packages/backing APIs. Production runtime and generator source audits also found no Viaduct internal API access. The retained handwritten prototype and engine-test instrumentation still use implementation APIs; they are not the supported production delegate path.

Schema-coverage regressions execute scalar and enum lists with nullable elements, empty/null lists, JSON (including nested values), UUID-backed untyped IDs, decimal/date/timestamp/numeric arrays, and isX public getters. Replacement, omission, and unchanged-flush behavior are checked. Timestamp-array equality uses Hibernate's public JavaType extension to compare instants, preventing UTC normalization from causing unnecessary UPDATEs. The Time/JSON-array follow-up also supports native jsonb[] lists and OffsetTime scalars/lists, preserving offsets and microseconds. Existing offset-less Time columns require a schema migration with an explicit interpretation of their old values.

Large-collection regressions verify 1,000 detached references with only the parent entity loaded, no initialized Hibernate bag, and visibility of pending native writes. ID-only modern connection queries cover forward/after/backward pages over 1,000 rows with zero entity hydration. The separate performance case compares detached IDs, cursors, and PageInfo between both query approaches and records allocations/latency.

Consumer build coverage verifies explicit opt-in compilation, unchanged HBM/GRT bytecode, and stale delegate removal when disabled. Loader regressions verify native resolver-only fields remain excluded, existing provider restrictions remain the default, and invalid persisted relationships still fail. Shared snapshot/diff and native/model regressions also pass.

The connection regression exposed a native proxy without request state in its proxy shell. Connection building now calls `Hibernate.unproxy` before inspecting the delegate. The synthesized-owner test assigns the required native parent before `persist`, preserving ordinary Hibernate FK/nullability behavior without adding a GRT field or special cascade logic.

Verification command (local PostgreSQL credentials supplied through the existing environment):

```sh
./gradlew :plugin:test \
  --tests 'dev.viaduct.persistence.postgresql.delegate.*' \
  --tests 'dev.viaduct.persistence.hibernate.*' \
  --tests 'dev.viaduct.persistence.model.*' \
  --tests 'dev.viaduct.persistence.postgresql.HibernateNativeIntegrationTest' \
  --tests 'dev.viaduct.persistence.gradle.GrtDelegatePluginTest' \
  --tests 'dev.viaduct.persistence.gradle.ViaductPgPersistencePluginTest' \
  --tests 'dev.viaduct.persistence.gradle.PersistenceSchemaModelLoaderTest' \
  --tests 'dev.viaduct.persistence.gradle.PersistenceConfigTest' \
  --tests 'dev.viaduct.persistence.gradle.SelectiveNodeSchemaTest' \
  --tests 'dev.viaduct.persistence.gradle.HibernateSchemaDiffTaskTest' \
  --tests 'dev.viaduct.persistence.gradle.ConservativeLiquibaseDiffTaskTest' \
  :runtime:test :plugin:delegatePerformanceTest :hibernate:ktlintCheck :plugin:ktlintCheck \
  :hibernate:detekt :plugin:detekt :hibernate:spotbugsMain \
  :plugin:spotbugsMain :plugin:spotbugsTest --no-parallel --continue
```

Verification logs: `/private/tmp/grt-time-json-verification.log` (223 cases passed; initial static-analysis failures), `/private/tmp/grt-time-json-quality-final.log` (final targeted cases and static checks), and `/private/tmp/grt-coverage-final3.log` (earlier performance comparison). Runtime bootstrap, GRT CRUD, native relationships, modern connections, and current limitations are described in `hibernate/DELEGATES.md`, linked from the repository and Hibernate READMEs.

These follow-up changes are included in PR #37. Existing providers and both consumer apps are untouched. Published experimental API compatibility, unsupported schema shapes, real RLS policies, application migrations, and production-scale performance validation remain explicit limits; the passing tests are not a claim that arbitrary application lock ordering cannot deadlock.

## Schema coverage and collection measurements — 2026-10-09

PostgreSQL; 5,000 related Person rows; 100-row forward connection; Java 21.0.2.
Three warmup rounds and 20 alternating-order samples per case. Each sample opens, initializes,
reads, commits, and closes a fresh session against the same data. Setup and compilation are excluded.
SQL statements include two transaction-local timeout settings. Allocation is measured on the IO
thread with the JDK ThreadMXBean; it excludes server allocation and unrelated threads.

| Operation | Median ms | p95 ms | Median allocated KiB | Entities loaded | Collections loaded | SQL statements |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Collection: entity hydration | 53.34 | 75.51 | 69685.70 | 5001 | 1 | 4 |
| Collection: ID projection | 17.67 | 26.68 | 17989.80 | 1 | 0 | 4 |
| Connection: entity hydration | 5.78 | 20.51 | 1507.98 | 101 | 0 | 3 |
| Connection: ID projection | 3.98 | 7.34 | 464.75 | 0 | 0 | 3 |

These are local exploratory measurements using Hibernate's test connection pool, small scalar
payloads, and warm database caches. They do not establish production throughput or latency.
Lists still materialize every reference. Connections fetch the requested page plus one row.

Reproduce with `./gradlew :plugin:delegatePerformanceTest --no-parallel` and the existing local PostgreSQL integration-test environment. Full source and results are in `docs/HIBERNATE_GRT_DELEGATE_PERFORMANCE.md`; the generated report is `plugin/build/reports/hibernate-delegates/performance.md`.

Each selected collection currently issues an ID query per parent/context. Repeated aliases or many parent collections may repeat queries; batch-query throughput has not been measured. Native collection mutations still use Hibernate's normal owning-side rules. No paging API was added beyond modern Viaduct connections.

## Prototype results — 2026-10-08

### Verdict

This section records the earlier experiment, which is **rejected and superseded** because its selected views used Viaduct internal constructors and engine backing data. It is retained only as development history and test instrumentation, not as the supported delegate implementation.

The current generated path uses public typed GRT getters and builders, public resolver contexts, GlobalIDs, node references, and selection sets. Each selected response is built with a fresh typed builder. This copies selected field values and preserves omission without sharing engine backing data. No zero-copy or performance improvement is claimed.

Inspection used `~/airlab/repos/viaduct` at HEAD `091c2198e9f2dfd86cd545c071b6e9e55b9a9c2a`; execution uses published Viaduct `2.1.0-20260922.061944-33` artifacts and Hibernate `7.3.4.Final`. Viaduct source and generated GRT bytecode remain unchanged.

### Implemented experiment

The proof lives locally under `plugin/src/test/kotlin/dev/viaduct/persistence/postgresql/delegate/`:

- `DelegatePerson`, `DelegateGroup`, and `DelegateMembership` are handwritten stand-ins for generated delegates. Scalars live in the current GRT; native UUIDs, associations, and collection wrappers remain Hibernate bookkeeping. `DelegateEntity` shares identity and hydration plumbing.
- `GrtDelegate` stages Hibernate hydration in a generated GRT builder, publishes an immutable GRT once on first access, and uses the existing `toBuilder()` overlay for later changes.
- `SelectedData` restricts field reads and presence while delegating to that immutable backing object. It never retains a Hibernate session or reads managed values after closure.
- `DelegateFixture` supplies a request binding through a per-session Hibernate interceptor and executes native PostgreSQL transactions. It enables real lazy proxies and unwraps them through Hibernate before accessing their GRT.
- `HibernateGrtDelegateIntegrationTest` and `HibernateGrtCollectionIntegrationTest` use actual Viaduct-generated bytecode and the real engine. Reflection bridges randomly named fixture GRTs; a production generator should emit typed construction and property access.

The delegate exposes GRT values and validated replacement operations to callers, with mutable assembly state kept private. Incomplete replacements are rejected before altering managed state. Native Hibernate dirty checking observes the replacement through mapped getters.

The test changes only Java representation/proxy settings in the generated metadata. It reuses the generated table definitions and native PostgreSQL migration SQL. No handwritten application SQL, Liquibase file edits, pg_graphql execution, response translation, or inherited query planner is used by the prototype.

At this prototype stage, the experiment had not been pushed to PR #37. Its tests are now included as historical instrumentation alongside the supported generated implementation; it does not replace the production client.

### Verification

**36 delegate integration cases and 2 targeted mapping regression cases passed.** The broader native Hibernate, mapping, and model run passed **139 cases, with zero failures, errors, or skips**. Each new test contains one assertion. Formatting, Detekt, and main/test SpotBugs checks passed; bytecode analysis reports zero findings, errors, or missing classes.

| Behavior                 | Evidence                                                                                                                                                                                                             |
| ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Persistence              | GRT insert with generated UUID, native load, replacement update, refresh, and remove succeed. The supplied pre-insert GRT stays unchanged.                                                                           |
| Dirty checking           | Hydration builds one GRT; flushing an unchanged row issues zero UPDATEs.                                                                                                                                             |
| Snapshot lifetime        | Cached and previously unread old fields remain stable after replacement, commit, refresh, and session closure.                                                                                                       |
| Presence                 | Explicit null clears the nullable value; untouched values survive `toBuilder()`; partial replacements fail before changing the entity.                                                                               |
| Shared selected views    | Two views of one managed identity have independent field availability and share immutable backing data. A selected view's `toBuilder()` cannot expose omitted base fields.                                           |
| Associations and proxies | A native association becomes a detached Viaduct node reference, including outside owned selections. An initially uninitialized Hibernate proxy can be loaded and exposed as a GRT while preserving managed identity. |
| Real GraphQL execution   | Aliases and nested references return actual child data. Checker denial preserves alias paths, sibling data, and non-null bubbling. Missing nodes preserve the expected error path and sibling data.                  |
| Transactions             | Ordinary failure, uniqueness violation, cancellation, and fatal-error cases roll back. Engine, rollback, and concurrent-context cases have deadlines.                                                                |
| Context isolation        | Concurrent sessions retain their own request bindings and GlobalID codecs without a global registry or thread-local.                                                                                                 |

Verification command, with local PostgreSQL credentials supplied through the existing environment:

```sh
./gradlew :plugin:test \
  --tests 'dev.viaduct.persistence.postgresql.delegate.*' \
  --tests 'dev.viaduct.persistence.hibernate.*' \
  --tests 'dev.viaduct.persistence.model.*' \
  --tests 'dev.viaduct.persistence.postgresql.HibernateNativeIntegrationTest' \
  :plugin:ktlintCheck :plugin:detekt :plugin:spotbugsMain :plugin:spotbugsTest
```

Latest complete run: `/private/tmp/grt-collections-final.log`. Test bytecode analysis receives runtime dependencies; narrow shared-reference exclusions cover only the fixture's factory and named native Hibernate association/collection fields whose managed identity and wrappers must be retained.

### Findings from failed iterations

A custom Hibernate `Interceptor.instantiate` must assign the supplied identifier, not just construct an entity. Omitting that assignment triggered a Hibernate loading assertion. A real-engine run with that malformed fixture stalled and was interrupted before explicit engine-test deadlines were added. The interceptor is corrected and current tests complete; the suite does not separately certify every fatal-error path inside Viaduct.

The initial mock resolver context created stub node references whose selection access throws. Real engine tests now use references from the actual engine execution, matching production Viaduct's reference lifecycle. This required a fixture correction, not an upstream API change.

When changing dynamic-map metadata to a lazily proxied POJO, both the mapped class and proxy class must be set. Updating only the class and lazy flag left an incomplete Hibernate proxy configuration. The prototype now supplies both, and the lazy-proxy test passes.

### Collection follow-up and design review

Hibernate already owns collection mappings, lazy loading, identity, and dirty checking. The new delegates retain its native collection wrappers without enumerating them during hydration. Generated selective collection reads now query child UUIDs through the mapped relationship and build detached Viaduct references while the session is open. They do not initialize the native bag or hydrate child entities; pending native writes are flushed normally before the query. It does not recursively project child graphs or add a filter language, query planner, index generator, or cascade engine.

The tests cover Group.members → Membership.person, membership roles and exact groupId @idOf names, independent join-table users/featuredUsers collections, and inverse Person.reports → manager. They verify lazy unselected collections, stable old snapshots after collection changes, owning-FK movement and deletion, join-table add/remove/rollback, parallel sessions, repeated aliases, real nested child data, access-check denial with list-index paths, and a finite cyclic graph.

Three problems were identified and fixed:

- Reverse FK collections used a synthesized parent column even when their owning field already existed. The native HBM generator now reuses that association's column, including exact @idOf names, and its nullability. This is a generator fix; no migration file was edited manually.
- Viaduct builders unwrap supplied lists into mutable internal lists. The earlier test-only SelectedData workaround is superseded; generated results use ordinary public builders and detached node references. Generated getters retain Viaduct's normal Kotlin read-only List contract; this does not strengthen Viaduct's API against callers deliberately casting and mutating getter results.
- Initial GRT relationships would otherwise be accepted without updating Hibernate's native associations. The handwritten prototype rejects supplied collections and uses native association setters. The generated runtime now validates and resolves owning to-one references through the active session before replacing the GRT. Collection writes remain native Hibernate operations; supplied collection values are rejected by GRT replacement validation.

A contended relationship transaction was tested with a PostgreSQL transaction-local lock timeout. The blocked transaction fails and rolls back, the lock holder completes, and a subsequent update/read succeeds. Cyclic GraphQL traversal and concurrent read tests also complete. This is focused evidence, not proof that arbitrary application lock ordering cannot deadlock. Coroutine cancellation alone does not interrupt blocking JDBC: production needs connection/statement/lock timeouts.

Remaining design constraints:

- Ordinary native Hibernate collection access still loads child rows, but the generated selective path uses ID projections. GraphQL lists still materialize every reference; use explicitly ordered modern Viaduct connections to bound large results. Connection queries can return native UUIDs without entity hydration.
- Native inverse collections persist changes through the owning association. Mutating only an inverse list is not a database update; the membership groupId and person manager tests update the owning side.
- The fixture's group.users is its own explicitly mapped join relationship. It is not an automatically inferred shortcut through membership.person. Reading that membership path needs ordinary association navigation or a native Hibernate join query.
- The earlier internal selected-view experiment is rejected. Automatic delegate generation now uses public typed builders; selected field values are copied. Scalar/enum arrays, JSON scalars/lists, offset-preserving Time scalars/lists, UUID-backed IDs, and isX fields have typed support. The object/relationship follow-up supports abstract Node relationships, stored edge GRTs, explicitly mapped non-Node objects, and ordinary bridge-vocabulary field names. The earlier rejection was a bridge limitation; the shared persistence model still controls eligible roots and targets. Actual RLS policies and application migrations remain unverified.

### Remaining work and next step

Automatic delegate generation, GRT-facing CRUD, selective node/batch reads, native associations and collections, and modern Viaduct connection assembly are implemented locally. The generator compiles typed delegates against unchanged generated GRTs. Runtime configuration changes Java representation/proxy settings while preserving physical schema metadata.

The optional generator is enabled with `delegateGrtPackage`; existing providers retain their defaults. Native schema consumers skip only pg_graphql-specific field restrictions. Persistence policy, relationship, and selective-node validation remain active.

Broader generated-runtime regressions and static checks passed, including session ownership, lock timeout recovery, existing proxies, and synthesized owners. Scalar/enum arrays, JSON scalar/list columns, offset-preserving Time scalars/lists, UUID-backed untyped IDs, and isX fields use typed public getters/builders. Abstract Node relationships, stored edges, and explicitly mapped non-Node objects now have concrete GRT delegates. Avoidable bridge-state name restrictions have been removed; genuine JavaBean accessor collisions and existing persistence-model restrictions still apply.

The bridge no longer conflates schema objects with storage rows. `GrtEntity<T : Object>` contains concrete object GRTs; Node identity has separate bindings and delegates. Synthetic storage rows remain native objects, while persisted edges contain their actual edge GRTs. Ordinary schema names no longer collide with bridge state. Native Hibernate transactions supply commit/rollback without retaining the old provider's buffered transaction API.

Actual application migrations are outside the current scope. Live app compatibility and actual authorization/RLS policies remain unverified. The generated collection path uses child-ID queries without hydrating children; modern connections support ID-only ordered queries. Local 5,000-row measurements compare both approaches with native entity hydration. Production throughput and latency remain unverified.

## Objective

Remove the separate persistent entity graph followed by a generic recursive projection into another object graph. Generate a small, explicit persistence delegate from the existing schema-derived model, leaving Viaduct's generated classes unchanged.

The efficiency target is to avoid a second full entity-to-GRT traversal and unnecessary duplicate graph storage. Loading database values into an object and Hibernate's own dirty-checking snapshots still cost work. No zero-copy or performance improvement is claimed until measured.

## What inspection establishes

| Finding                                                                                                                                                                                                                        | Design consequence                                                                                                                                                                                        |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| The installed Viaduct API is `2.1.0-20260922.061944-33`; Hibernate is `7.3.4.Final`.                                                                                                                                           | The first prototype must compile and run against these versions. Current Treehouse source provides additional explanation, but is not a substitute for validating the installed artifacts.                |
| Output GRTs extend `ObjectBase`. Construction needs an internal Viaduct context and an `EngineObject`. Generated getters read that backing object rather than ordinary mutable entity fields.                                  | External Hibernate mappings alone cannot turn these classes into normal mutable Hibernate POJOs.                                                                                                          |
| GRT getters cache values. Builders produce new backing data; `toBuilder()` uses an overlay that preserves unchanged values.                                                                                                    | Replace the GRT on an update. Never mutate the backing state of a published GRT or reflectively clear its cache.                                                                                          |
| Hibernate exposes property-access and entity-instantiation extension points, plus per-session interceptors. `SessionBuilder.interceptor(...)` and `Interceptor.instantiate(...)` are present in the installed version.         | Per-session construction, native identity assignment, association proxies, hydration, and refresh are verified in the tested delegate fixtures. No shared request context is needed. |
| Selective Viaduct resolvers distinguish requested selections from resolver-owned selections. Existing tests require unselected fields to remain unset and requested node relationships outside ownership to remain references. | One complete managed GRT cannot simply be returned for every resolver invocation. Persistence state and each resolver's permitted view need explicit treatment.                                           |
| The current generated HBM model uses dynamic maps, native identifiers, and Hibernate associations.                                                                                                                             | Runtime delegate mappings must keep the existing table, column, constraint, index, and ownership definitions. A delegate is a runtime representation change, not a new database model.                    |
| `GrtProjection` reuses `NodeReferencePlanner`, `PagingAccess`, and `GeneratedTypeReflection`; those carry pg_graphql translation and compatibility machinery.                                                                  | Hibernate-specific generation must use the semantic persistence model, Hibernate metadata, and Viaduct reflection directly. The optional delegate execution path does not use these dependencies; the existing dynamic-map bridge retains its own implementation.                  |

Relevant current code: [GrtProjection](https://github.com/viaduct-dev/pg-persistence/blob/7d98fec18da833bcdcaa51c308b07f8ec2cac4f7/runtime/src/main/kotlin/dev/viaduct/persistence/runtime/grt/GrtProjection.kt), [HibernateClient](https://github.com/viaduct-dev/pg-persistence/blob/7d98fec18da833bcdcaa51c308b07f8ec2cac4f7/hibernate/src/main/kotlin/dev/viaduct/persistence/orm/HibernateClient.kt), [HibernateObject](https://github.com/viaduct-dev/pg-persistence/blob/7d98fec18da833bcdcaa51c308b07f8ec2cac4f7/hibernate/src/main/kotlin/dev/viaduct/persistence/orm/HibernateObject.kt), and [generated mapping template](https://github.com/viaduct-dev/pg-persistence/blob/7d98fec18da833bcdcaa51c308b07f8ec2cac4f7/plugin/src/main/resources/dev/viaduct/persistence/hibernate/viaduct-persistence.hbm.stg).

## Options and recommendation

| Option | Assessment |
| --- | --- |
| Map unchanged GRT classes directly as ordinary Hibernate entities. | Their constructor and property shape do not support ordinary mutable POJO mapping. Approaches requiring Viaduct internal construction or backing-data access are rejected. |
| Generate a normal Hibernate delegate containing the current GRT. | Chosen and implemented. Generate public typed getters and builder calls; keep mutable hydration and native relationship bookkeeping inside the delegate. |
| Give GRTs custom Hibernate-derived engine backing data. | Rejected because it relies on Viaduct internal APIs, even if it reduces selected-field copying. |
| Keep dynamic-map entities and simplify their projection. | Remains a separate existing provider, not the endpoint of this optional GRT-first approach. |

The supported delegate path builds selected results through fresh typed builders. It accepts the cost of copying selected values rather than depending on engine backing data. Hibernate still owns persistence, native identity, associations, collections, and transactions.

## Delegate lifecycle

A generated delegate owns a current GRT reference and only the metadata or association bookkeeping Hibernate actually needs. The GRT is the authoritative application value; avoid maintaining independent scalar fields permanently alongside it.

1. During hydration, Hibernate supplies mapped values. The delegate needs a temporary assembly state until it has enough values and a request context to construct the GRT. Finalize once, rather than creating a new GRT after every hydration setter.
2. After assembly, scalar property getters read the current GRT, converting to native database representations only where necessary.
3. An application builds a replacement GRT using its generated builder. The persistence bridge updates the existing managed delegate's current reference; Hibernate compares mapped property values and issues SQL.
4. A previously returned GRT continues to represent its old value. Publishing a replacement must not alter it.
5. Generated identifiers and refreshes produce replacement GRT values. A newly created GRT with an unset ID must never have its backing data mutated when Hibernate assigns the identifier.
6. Rollback leaves the database unchanged. A transaction-local replacement is not advertised as durable before commit; after failure, discard the session and reload rather than treating that GRT as managed persisted state.

The implemented per-session interceptor creates a request-bound delegate and assigns the supplied native identifier. Hydration setters stage values in a typed GRT builder; the first value access publishes the completed GRT. Later hydration/refresh starts a new builder overlay. There is no request-specific state in the shared SessionFactory, generated registry, or a thread-local.

Request context must exist before materializing a GRT. One Hibernate session belongs to one synchronous transaction execution; it must not be shared by concurrently running resolvers. Hibernate's normal persistence context owns row identity. Do not add a second generic identity manager.

## Application API

`DelegateHibernateClient` is the optional GRT-facing persistence entry point. Its `insert`, `find`, `update`, and `delete` operations accept/return generated values or GlobalIDs; `transaction` supplies a native Hibernate Session. Existing clients remain separate approaches. No filter DSL, replacement Session API, or generic repository framework was added.

Example using the implemented API:

```kotlin
persistence.transaction(ctx) { session ->
    val group: Group = persistence.find(ctx, session, ctx.arguments.groupId)
    val renamed = group.toBuilder()
        .name(ctx.arguments.name)
        .build()
    persistence.update(ctx, session, renamed)
}
```

`find` supplies persisted scalars and owning to-one relationships for a complete write snapshot. `fetchNode` follows selective resolver ownership. A partial resolver response cannot be used blindly as a complete replacement. Updates validate presence, identity, target types, and paired object/ID consistency before replacing managed state.

Native HQL/Criteria express database conditions. Raw queries return generated delegates such as `GroupEntity`, not `Group` GRTs. Native association/collection changes stay within the transaction. Required synthesized owners must be assigned on a generated entity before native `Session.persist`; they have no GRT field.

`fetchConnection` uses modern Viaduct arguments and generated connection builders over a native ordered entity query. No separate public paging interface was added. See `hibernate/DELEGATES.md` for configuration and concrete examples.

## Partial values and mutation inputs

An unset output field and an explicitly null output field are different states. Calling getters for every mapped field on a partial GRT is unsafe; replacing omitted columns with null is incorrect.

Start with updates that load the required mapped state and then use `toBuilder()` to replace it. For incomplete GRTs, fail explicitly unless the bridge can establish a presence-aware update against the loaded entity. Do not infer a complete replacement from a selective response.

Mutation input GRTs are not necessarily the same class or shape as persistent output GRTs. Keep the distinction between input omission and explicit null. Do not automatically infer create/connect/delete behavior from arbitrary inputs or response selections. Any future generated input mapping must have explicit semantics; the earlier mutation-repository proposal is separate and is not implemented by this plan.

## Relationships and selections

A Viaduct node reference and a Hibernate association are different things. A delegate may need a native association reference for ownership, cascades, or persistent collection bookkeeping. The GRT-facing relationship uses Viaduct references.

Use exact existing mapping names and native identifiers. Convert GlobalIDs at this boundary and validate their target types. Resolve associations through Hibernate references within the active session, using the existing owning side. Do not recursively copy an entire related entity graph to build a node reference.

Tests cover to-one relationships, a membership association entity, and the unidirectional parent-FK pattern used by Group.members. Native references and persistent collections remain Hibernate bookkeeping; related GRT graphs are not recursively copied.

For selective reads:

- Respect owned scalar fields and requested relationship references.
- Preserve aliases, repeated identities with different selections, missing-node behavior, checkers, and non-null propagation.
- Keep unselected fields unset. Do not reuse a broader GRT in a narrower resolver context.
- Ensure published GRTs retain only detached values or normal Viaduct references, never lazy Hibernate collections or a live session.

Raymie's selection-set concern exposed a gap: the previous fetchNode/fetchNodes implementation used Session.find/multiLoad, which loaded every mapped scalar before building a narrower GRT. The new selective read path issues Hibernate HQL tuple projections and builds the GRT directly from native column values. It reads row identity, owned scalar fields, and the foreign-key identities needed for requested relationships. Identity is required to check existence and assemble references even when GraphQL does not select id; that does not expose id in the returned GRT.

Batch contexts with identical parent column sets share a query. Each context retains its own nested SelectionSet and result builder; a wider sibling does not enlarge a narrower sibling's column projection. Nodes remain ordinary Viaduct references and resolve their own child selections. Nested ordinary objects use their finite child selections for separate projections; each ordinary-object list loads its selected scalar fields together rather than hydrating one entity per child. Cycles terminate with the finite selection tree.

Managed delegates remain the native write representation. Read projections are never installed as partially hydrated managed entities, so omitted fields cannot be flushed as null. Native entity queries and find/select/project helpers still load managed entities normally; shaping an already loaded entity cannot undo that SQL. Custom selective reads now use `read(ctx, session, binding, selections)` with a generated binding and a normal Hibernate Criteria callback. The binding-aware `fetchConnection(ctx, selections, rowBinding)` overload follows the same path. Applications supply predicates, joins, ordering, distinctness, and bound parameters; the supplied Criteria SELECT already projects the generated native property paths and must remain intact. This uses public Criteria and tuple-transformer APIs, without parsing HQL or inspecting Hibernate query implementations.

Node connection rows select identity only and become Viaduct references. Persisted edge rows select edge identity, concrete node FKs, and requested edge fields; the explicit association binding disambiguates shared edge GRTs. Ordinary object connection nodes merge the finite `nodes` and `edges.node` selection trees. GRT builders run after query execution, inside the session, so nested ordinary-object projections do not reenter Hibernate's row hydration. Connections count the filtered query when Viaduct needs a total, then fetch its ordered slice plus one lookahead row. The extra row is not turned into a GRT. Native entity queries and the original connection overload retain full Hibernate hydration when explicitly chosen; arbitrary existing SelectionQuery instances are not automatically rewritten. PostgreSQL's usual DISTINCT/order restrictions still apply.

All 92 affected generated-runtime, generator, and real-consumer cases passed across the final runs, including eight Node SQL-selection cases and 15 custom-query/connection SQL-selection cases, with zero failures, errors, or skips. Three SQL-level regressions failed against the previous implementation: a single selected scalar, heterogeneous batch contexts, and a finite nested ordinary-object tree. Additional SQL checks cover FK-only and typename-only reads, compatible batching, an ordinary-object list sharing its scalar query, and aliased parent/child data through the real Viaduct engine. The custom-query tests cover bound predicates, forward/backward paging, distinct joined counts, omitted edge owner/reviewer fields, aliased selections, abstract targets, shared edge mappings, nullable FKs, merged ordinary-object connection trees, and rejection of replaced projections or application paging (including embedded Criteria offset/fetch clauses). Each new test has one assertion. Formatting, Detekt, and Hibernate/plugin main/test SpotBugs checks passed. The implementation uses public Viaduct selections, typed builders, and Hibernate APIs; no internal storage access or JSON wire protocol is introduced.

Modern connections continue to use Viaduct's ConnectionFieldExecutionContext, its argument validation/bounds, OffsetCursor, and generated connection builders, including the published experimental fromEdges method. Hibernate supplies ordered slices and counts. No pg_graphql cursors, connection protocol, or legacy collection support is involved.

## Generation and dependency boundaries

The opt-in generator uses the assembled GraphQL schema and existing semantic/Hibernate mappings. It reuses `pg-persistence.yaml` for database-specific policy and emits `<GRTName>Entity` classes plus `PersistenceDelegates.bindings` under `<grtPackage>.persistence`. Viaduct GRTs and schema source files are unchanged.

Set `viaductPgPersistence.delegateGrtPackage` to enable `generateViaductHibernateDelegates`. Outputs live in `build/generated/viaduct-grt-delegates/kotlin` and join the main Kotlin source set. Disabling the option removes stale generated sources. Delegate compilation uses the consumer's normal Viaduct codegen; schema-only tasks do not depend on compiling delegates.

Resolver-only fields are excluded from persistence unless they describe an existing stored relationship. With the native option enabled, shared schema tasks retain semantic validation but skip pg_graphql's field restrictions. Default provider validation remains unchanged.

Call `PersistenceDelegates.bindings.configure(metadata)` before building the runtime SessionFactory. It first checks every mapped property has generated bean accessors, then changes only Java representation/proxy settings. The schema-generation HBM stays dynamic-map based; runtime class selection is not a schema migration.

The delegate execution path has no pg_graphql protocol, filter DSL, generic graph projection, or planner. Shared module dependencies still package the existing runtime; the existing providers are preserved. The production delegate runtime and generated sources have no Viaduct internal imports, constructors, backing-data access, or engine selections. Text IDs use the public resolver-context serializer. Published experimental reflection/connection APIs are explicitly opted into; no InternalApi opt-in is required.

Request-bound construction uses Hibernate's supported per-session Interceptor.instantiate extension. Its signature includes EntityRepresentationStrategy, which the bridge does not inspect or call. A private wrapper delegates to the public Session interface and verifies client/request ownership without inspecting Hibernate implementation objects, a shared registry, or thread-local storage.

Unsupported shapes fail generation explicitly. Native array/JSON mapping in the separate dynamic-map bridge does not establish delegate support for those columns.

## Implementation plan

### 1. Prove the scalar delegate

Use one generated fixture type, with ID, string, nullable string, enum, and timestamp. Map a generated delegate through normal Hibernate metadata. Test context-aware hydration, insert with generated UUID, update through a replacement GRT, refresh, and remove on PostgreSQL.

Confirm finalization happens once per hydration and that reading/flushing an unchanged entity produces no UPDATE. Record whether custom property access or an interceptor is actually needed. Discard unnecessary extension points.

**Gate:** the application works with unchanged GRTs; the delegate has no permanent duplicate scalar record or pg_graphql dependency.

### 2. Prove presence, views, and lifecycle

Test updates built with `toBuilder()`, incomplete GRTs, explicit null, input omission, and generated IDs. Read an old GRT getter before an update, then verify both old and replacement GRTs after commit and session closure.

Test a single row returned under two different selective contexts, including a requested relationship outside owned selections. Verify fresh public builders include only selected fields and use no internal APIs. Count allocations and field traversals separately before making performance claims.

**Gate:** selection behavior is correct, no cached value is mutated, no omitted column is accidentally cleared, and no production dependency on Viaduct internal APIs is introduced.

### 3. Prove app relationships

Exercise Group, Person, GroupMember, and ChecklistItem patterns from Gateloom and batteries-included. Test exact `@idOf` names, owning associations, FK collections, joins, list/search predicates, and delete/constraint behavior using native Hibernate.

Keep Hibernate responsible for association persistence and collection tracking. Avoid adding a second cascade engine.

**Gate:** associations work without lazy managed objects escaping or a separately traversed persistent graph.

### 4. Prove execution behavior and failure safety

Run real Viaduct engine tests for aliases, nested references, checker denial, missing nodes, non-null propagation, and batches with differing selections. Exercise parallel resolver completion and transaction failure/cancellation with deadlines.

Use explicit PostgreSQL lock/query timeouts in blocking tests. Roll back on ordinary exceptions, database constraint failures, cancellation, and fatal errors. Prove context/role/RLS initialization remains per transaction.

**Gate:** no additional session sharing, nested transaction waits, context leakage, or deadlocks; failure behavior follows native Hibernate and existing Viaduct semantics.

### 5. Integrate the optional bridge and document

Generate delegates for supported persisted types and expose them through the optional `DelegateHibernateClient`. Preserve `DbClient` and the dynamic-map `HibernateClient` as separate approaches. The delegate path uses generated bindings and small Viaduct helpers without invoking their projection or protocol code.

Keep modern connection behavior and schema generation intact. Document unsupported shapes explicitly. Leave Gateloom and batteries-included unchanged.

PR #37 includes a production follow-up commit and a separate test/documentation follow-up commit, preserving the original pushed commits.

## Verification and acceptance

| Area                      | Required evidence                                                                                                                                                                                                          |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Native persistence        | PostgreSQL insert/read/update/remove through GRT-facing APIs, generated UUIDs, ordinary Hibernate dirty checking, and no pg_graphql extension/overlay dependency.                                                          |
| GRT stability             | Old cached and uncached getters stay stable after replacement, flush, refresh, and session closure.                                                                                                                        |
| Presence                  | Omission preserves values; explicit null clears nullable values; incomplete replacements cannot silently erase fields.                                                                                                     |
| Identity and associations | Repeated row identity uses Hibernate's persistence context; relationships use correct GlobalIDs and owning mappings; cycles do not trigger recursive projection.                                                           |
| Selection and GraphQL     | Owned/requested selections, unset fields, aliases, checkers, missing nodes, and non-null behavior pass real engine tests.                                                                                                  |
| Connections               | Forward/backward/default/empty pages use only modern Viaduct connection APIs and deterministic SQL ordering.                                                                                                               |
| Concurrency and failures  | Bounded parallel resolvers, isolated sessions/contexts, transaction rollback, transaction-local initialization, and database lock timeouts. Actual RLS policy testing remains separate.                                    |
| Schema generation         | Generated-fixture physical metadata and consumer HBM/GRT bytecode remain unchanged by delegate configuration. No application migration changes; full sample regeneration is separate.                                      |
| Simplicity and efficiency | Delegate execution does not invoke the retained provider projection/planner. Selected values use public typed getters/builders without internal APIs. Local 5,000-row latency/allocation measurements and entity/collection counts are recorded. Production-scale throughput remains unverified. |
| Consumer scope            | Run representative native tests first; migrate and test Gateloom and batteries-included separately before claiming live application compatibility.                                                                         |

New tests should have one assertion per test. Existing provider-comparison tests may inform regression cases, but the Hibernate acceptance suite must run without requiring pg_graphql.

## Open questions to settle before expansion

Normal generated delegates assemble and replace GRTs through typed builders, ordinary Hibernate properties, and a per-session interceptor. Lazy builder finalization handles hydration, refresh, and generated IDs. Selected responses copy selected values through fresh public typed builders and do not use Viaduct engine backing data or internal APIs.

Scalar/enum arrays, JSON scalars, UUID-backed untyped IDs, and isX fields now have a public typed implementation. Large selected relationships use ID projections; modern connections also accept ordered node UUID queries. Local allocation/latency measurements are available, and the expanded regression tests pass.

The remaining decisions concern published experimental API compatibility, existing Node-only root discovery and abstract-target model constraints, actual RLS policies, and production-scale throughput results. Explicitly mapped non-Node objects need their existing schema UUID identity; this bridge does not invent one. Consumer migrations, including existing offset-less Time columns, require separate authorization and validation; they are outside this opt-in implementation.

Physical-schema equivalence is checked with generated runtime fixtures. Consumer build tests cover opt-in compilation, unchanged mapping/GRT bytecode, and stale-source removal. This is distinct from certifying all existing app migrations or every possible schema customization.
