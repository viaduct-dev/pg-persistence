# Hibernate GRT Mutation Repository Slate

## Status

Proposed.

## Summary

Add a Hibernate-only Viaduct mutation repository that accepts generated runtime type (GRT) mutation
inputs, maps them to the generated dynamic Hibernate model, and persists the resulting object graph
in one transaction. Applications should be able to implement a mutation resolver by passing its
typed input to a generated repository instead of manually copying every field and coordinating
inserts for every related type.

This feature uses Hibernate and JDBC for runtime persistence. It is independent of the
`pg_graphql` database client and does not translate writes into GraphQL operations.

## Motivation

Viaduct mutation resolvers already receive typed GRT arguments and return typed GRT payloads. The
persistence plugin already derives dynamic HBM metadata, identifiers, relationships, join tables,
and constraints from the same assembled GraphQL schema. Requiring each application to
write another hand-maintained mapping and persistence service leaves the two generated models
artificially disconnected.

For a mutation such as:

```graphql
input CreateGroupInput {
  name: String!
  persons: [CreatePersonInput!]!
}

input CreatePersonInput {
  displayName: String!
}

type Mutation {
  createGroup(input: CreateGroupInput!): CreateGroupPayload!
}
```

the resolver should be able to delegate the graph write:

```kotlin
@Resolver
class CreateGroupResolver(
    private val groups: GroupMutationRepository,
) : MutationResolvers.CreateGroup() {
    override suspend fun resolve(ctx: Context): CreateGroupPayload {
        val group = groups.persist(ctx.arguments.input)
        return CreateGroupPayload.Builder(ctx)
            .group(ctx.ref(ctx.globalIDFor(Group.Reflection, group.id)))
            .build()
    }
}
```

The generated repository maps the input GRT to dynamic Hibernate entity maps behind a typed API.
Hibernate owns cascade traversal, insert ordering, association-table writes, identity tracking, and
transaction atomicity.

## Goals

- Generate type-safe mappings from mutation input GRTs to dynamic Hibernate entity graphs.
- Provide repository-level `persist`, `merge`, and `remove` operations suitable for Viaduct
  mutation resolvers.
- Reuse the effective Hibernate mapping as the authority for identifiers, relationship ownership,
  cascades, join tables, and orphan behavior.
- Execute one repository operation in one Hibernate transaction.
- Decode Viaduct global IDs before assigning Hibernate entity identifiers or references.
- Preserve the distinction between an omitted input field, an explicit `null`, and an empty
  collection when the GRT API exposes that distinction.
- Generate compile-time diagnostics for ambiguous or impossible mappings.
- Return a stable entity result that a resolver can use to construct a payload or node reference.

## Non-goals

- Sending writes through `pg_graphql` or any other GraphQL database frontend.
- Reimplementing Hibernate's persistence context, dirty checking, cascade engine, or SQL planner.
- Inferring create, connect, update, or delete intent from an arbitrary output selection set.
- Treating output selections as mutation values. Selection sets describe the requested response;
  mutation input GRTs contain the values to persist.
- Automatically exposing CRUD fields in the public GraphQL schema.
- Making every input object persistent merely because its fields resemble an entity.
- Hiding application authorization. Viaduct checker executors and mutation resolvers remain
  responsible for deciding whether an operation is allowed.

## Repository semantics

The API follows repository-level Hibernate semantics rather than attempting to expose the entire
`EntityManager` lifecycle:

```kotlin
data class PersistedNode<ID : Any>(val id: ID)

interface MutationRepository<I : GRT, ID : Any> {
    suspend fun persist(input: I): PersistedNode<ID>
    suspend fun merge(id: ID, input: I): PersistedNode<ID>
    suspend fun remove(id: ID): Boolean
}
```

Generated repositories may provide operation-specific names and input types:

```kotlin
interface GroupMutationRepository {
    suspend fun persist(input: CreateGroupInput): PersistedNode<String>
    suspend fun merge(id: String, input: UpdateGroupInput): PersistedNode<String>
    suspend fun remove(id: String): Boolean
}
```

`persist` creates a new root and follows Hibernate persist cascades. `merge` loads or obtains a
reference to the root, applies fields present in the input, and follows configured merge behavior.
`remove` obtains the root and lets Hibernate enforce configured cascades and database constraints.

The repository does not expose dynamic managed entity maps outside its transaction as though they
remain attached. It returns an immutable generated result containing the root identifier and any
explicitly supported scalar result values.

## Mapping declarations

Input names cannot always identify persistence intent safely. Mapping should be generated only when
the relationship between a mutation input and a persistent object is explicit.

The preferred source is a Viaduct schema-level association between the mutation argument and its
target persistent type. If Viaduct does not expose such an association, add a persistence directive
or schema-adjacent configuration rather than relying on naming conventions alone. For example:

```yaml
hibernateMutations:
  inputs:
    CreateGroupInput:
      entity: Group
      operation: persist
    UpdateGroupInput:
      entity: Group
      operation: merge
```

Nested input mappings are resolved recursively. A nested input must declare one unambiguous intent:

- `create`: construct a new related entity;
- `connect`: decode an ID and obtain a Hibernate reference;
- `update`: apply fields to an existing related entity;
- `disconnect`: remove an association without deleting the target;
- `delete`: remove the target when the mapping permits it.

A first version may support only `create` and `connect`. Unsupported intent must fail generation or
resolver execution explicitly; it must not be guessed.

## Generated mapping

Generate ordinary Kotlin mapping code rather than discovering assignments reflectively on every
request. The generated code writes to Hibernate's dynamic-map representation using property names
validated against the effective model. Given a declared `CreateGroupInput -> Group` mapping,
generation produces the equivalent of:

```kotlin
internal fun CreateGroupInput.toNewGroup(context: MutationMappingContext): MutableMap<String, Any?> {
    val entity = context.newEntity("Group")
    entity["name"] = name
    entity["persons"] = persons.map { it.toNewPerson(context) }.toMutableSet()
    return entity
}
```

`MutationMappingContext` contains an operation-scoped identity map. It prevents duplicate entity
instances when the same global ID appears along multiple paths and provides typed ID decoding and
`EntityManager.getReference` for connect operations.

Generation validates mappings against both models:

1. Resolve the input GRT and target persistent object in the assembled Viaduct schema.
2. Resolve the target's Hibernate entity name, properties, and identifier through the effective
   model.
3. Match explicitly configured fields.
4. Validate scalar coercion and nullability.
5. Resolve object fields through Hibernate relationship metadata.
6. Resolve collection relationships, including association entities with custom edge fields.
7. Emit typed mapping and repository source.

## Relationship behavior

### To-one relationships

A nested create constructs the target entity and assigns it to the owning property. A connect input
decodes the target global ID and obtains a Hibernate reference. Generation fails when configuration
assigns a relationship from the inverse side without a corresponding ownership strategy.

### To-many relationships

The mapper constructs the target collection and, for bidirectional relationships, sets both sides
before persistence. Hibernate's effective relationship mapping determines whether persistence uses
a target foreign key or a join table.

### Association types and edge fields

Connections with persistent edge fields require generated association entities. The mapper creates
one association entity per edge, assigns owner and target references, and copies edge fields to the
association entity. This behavior must use the existing effective association metadata rather than
derive a second join-table convention.

### Cycles and repeated identities

The operation-scoped identity map registers an entity before traversing its nested relationships.
This terminates cycles and ensures repeated connect references resolve to one managed identity.
Creating two distinct input objects with the same explicitly supplied new identifier is an error.

## Input presence and update behavior

Update mapping requires field-presence information. These inputs are different:

```graphql
{ displayName: null }
```

```graphql
{}
```

The first clears a nullable value; the second leaves it unchanged. If the generated input GRT API
cannot distinguish them, automatic partial merge is unsafe and must wait for presence-aware GRT
support. It must not treat every nullable getter returning `null` as an instruction to clear data.

Likewise, an omitted collection leaves associations unchanged, while an explicitly supplied empty
collection replaces them with no associations only when the declared operation uses replacement
semantics. Patch, append, disconnect, and replacement should be distinct declared behaviors.

## Transactions and coroutine integration

Each repository operation executes inside one transaction and on a dispatcher appropriate for
blocking JDBC work:

```kotlin
suspend fun persist(input: CreateGroupInput): PersistedNode<String> =
    hibernateTransactionExecutor.write {
        entityManager.persist(input.toNewGroup(mappingContext()))
        entityManager.flush()
        materializeResult(input)
    }
```

The transaction executor owns `EntityManager` creation, commit, rollback, and exception translation.
It must never carry a Hibernate session across coroutine suspension or share an `EntityManager`
between requests. Cancellation rolls back an active transaction.

Constraint violations should retain their database cause while being translated into a stable
repository exception that a mutation resolver can convert to Viaduct user errors.

## Authorization boundary

Automatic persistence does not imply automatic authorization. Checker executors should validate
the mutation and relevant input nodes before the repository begins a transaction. Application code
may perform additional invariant checks inside the transaction when they depend on locked database
state.

The mapper must not recursively invoke public Viaduct mutation resolvers for nested objects. It
recursively constructs one Hibernate entity graph and submits that graph to one repository
operation.

## Diagnostics

Generation errors should name the input coordinate, entity coordinate, and conflicting mapping.
Examples:

```text
CreateGroupInput.persons maps to Group.persons, but no mutation mapping is declared for
CreatePersonInput -> Person
```

```text
UpdateGroupInput.name is nullable and its GRT does not expose field presence; partial merge cannot
distinguish omitted from explicit null
```

```text
CreateGroupInput.members maps to association Group.members, but edge input field 'role' has no
matching persistent edge attribute
```

## Delivery stages

1. Generate create-only scalar mappings and a transaction executor.
2. Add to-one nested create and explicit connect-by-ID.
3. Add to-many relationships and ordinary join tables.
4. Add association entities and persistent edge fields.
5. Add presence-aware partial merge.
6. Add disconnect, replacement, orphan removal, and delete semantics.

Each stage should expose only the semantics it implements. Later stages must not be simulated with
implicit conventions in earlier releases.

## Test plan

- Generation tests for input-to-entity declarations, scalar coercion, nullability, and diagnostics.
- Runtime tests proving a root and nested created entities commit in one transaction.
- Rollback tests proving a nested constraint failure leaves no partial graph.
- To-one ownership tests for new and connected targets.
- To-many tests for foreign-key and join-table mappings.
- Association tests for edge fields and bidirectional relationships.
- Identity-map tests for repeated references, cycles, and conflicting new IDs.
- Global-ID tests for correct target-type validation and decoding.
- Merge tests distinguishing omitted, explicit-null, empty, and populated fields.
- Cascade and orphan-removal tests demonstrating behavior comes from Hibernate metadata.
- Coroutine cancellation and concurrent-request isolation tests.
- Resolver integration tests showing checker execution occurs before persistence and repository
  failures become stable user errors.

## Acceptance criteria

- A Viaduct mutation resolver can persist a declared input GRT without handwritten field copying.
- A nested create persists the complete supported entity graph through Hibernate in one transaction.
- Relationship ownership and association tables come from the dynamic Hibernate mapping.
- Failed nested persistence rolls back the root and every related write.
- Global IDs used for connect operations are decoded and checked against the expected node type.
- Cyclic and repeated references do not recurse indefinitely or create duplicate managed identities.
- Unsupported or ambiguous mappings fail explicitly at generation time where possible.
- No part of this feature sends runtime database operations through `pg_graphql`.
