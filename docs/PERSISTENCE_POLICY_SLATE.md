# YAML Persistence Policy Slate

## Status

Proposed.

## Summary

Move persistence selection policy out of the `viaductPgPersistence` Gradle DSL and into a YAML file
that travels with the GraphQL schema. Persistence remains discovery-first: eligible Viaduct `Node`
objects are persisted unless they are named in a denylist.

The same YAML file can declare **semantic non-null** coordinates. These declarations allow the
database model to require values even when the public GraphQL schema keeps the corresponding fields
nullable for compatibility or partial-response behavior.

## Goals

- Replace the Gradle `includedTypeNames` allowlist with a YAML `denyList`.
- Keep the default zero-configuration behavior: discover and persist eligible `Node` object types.
- Support semantic non-null at either a type coordinate (`Group`) or field coordinate
  (`Group.name`).
- Validate configuration early and produce errors that identify the YAML key and coordinate.
- Use one schema-adjacent configuration file for persistence policy and relationship overrides.
- Make the YAML file a declared Gradle task input so changes invalidate task outputs and caches.

## Non-goals

- Changing GraphQL execution nullability or rewriting the assembled GraphQL schema.
- Allowing denied types to be pulled back into persistence through another setting.
- Defining semantic non-null for arguments, input fields, list elements, or resolver-only fields.
- Replacing Gradle settings unrelated to schema policy, such as generated package names or schema
  diff credentials.

## Configuration file

The conventional location is:

```text
src/main/viaduct/persistence.yaml
```

The file is optional. An absent file is equivalent to an empty configuration. Gradle retains a
`persistenceConfigFile` property only to override the location; the policy itself is never embedded
in `build.gradle.kts`.

```kotlin
viaductPgPersistence {
    persistenceConfigFile.set(layout.projectDirectory.file("config/persistence.yaml"))
}
```

The existing `relationshipConfigFile` convention and
`src/main/viaduct/persistence-relationships.yaml` file are replaced by this unified file.

## YAML shape

```yaml
denyList:
  types:
    - AuditEvent
    - RemoteProfile

semanticNotNull:
  types:
    - Group
  fields:
    - Person.displayName
    - GroupMember.person

relationships:
  unidirectionalTargetForeignKeyFields:
    - Group.members
  inverseFieldOverrides:
    ExternalGroup.discordServerRoles: server
```

The explicit `types` and `fields` keys avoid guessing whether a string is intended to be a type or
field coordinate. Keys are case-sensitive and use authored GraphQL names.

Unknown top-level or nested keys are errors. Values must have the documented YAML types; the loader
must not silently coerce scalars, maps, or lists to strings. Duplicate list entries are errors so a
configuration remains intentional and reviewable.

## Denylist behavior

Persistence type selection becomes:

1. Discover eligible object types: every object that implements `Node`.
2. Resolve every `denyList.types` entry against the assembled schema.
3. Reject an entry that is unknown, is not an object type, or is not an eligible discovered type.
4. Subtract the validated denylist from the discovered set.
5. Build and validate the persistence model from the remaining types.

An empty or absent denylist persists every discovered type. This makes newly added eligible types
persistent by default; excluding one requires an explicit, reviewable YAML change.

If a retained type has a stored relationship to a denied type, generation fails with an error that
names the relationship coordinate and denied target. A denied type must not cause a relationship to
be silently dropped or converted into a scalar. Resolver-only fields continue to follow their
existing rules.

`includedTypeNames` is removed from the public extension and all task types. There is no transition
state in which an allowlist and denylist are combined, because precedence between them would make
selection difficult to reason about.

## Semantic non-null behavior

Semantic non-null is a persistence constraint, not a GraphQL type modifier. Given this schema and
configuration:

```graphql
type Person implements Node {
  id: ID
  displayName: String
}
```

```yaml
semanticNotNull:
  fields:
    - Person.displayName
```

`Person.displayName` remains nullable in GraphQL, but its dynamic Hibernate property and relational
column are non-null, and schema diff proposes a `NOT NULL` constraint.

### Type coordinates

A coordinate in `semanticNotNull.types` applies to every persistable field declared on that object
type, including an owning to-one relationship's foreign key. It does not apply to resolver-only
fields, computed fields, or to-many containers, because those do not map to a nullable column on the
owning entity. It does not cascade to fields on related object types.

### Field coordinates

A coordinate in `semanticNotNull.fields` applies only to that stored field. Valid targets are basic
attributes, scalar arrays, and stored to-one relationships. For a scalar array, the declaration
controls whether the column/property itself may be null; it does not alter element nullability.

The following are configuration errors:

- an unknown type or field coordinate;
- a coordinate on a denied or otherwise non-persistent type;
- a field that is resolver-only, computed, or represented solely as a to-many relationship;
- an interface, union, enum, scalar, input type, argument, or input-field coordinate;
- a coordinate whose syntax is not exactly `TypeName` or `TypeName.fieldName`, as appropriate.

Declaring an SDL non-null field as semantic non-null is allowed but redundant. The model remains
non-null. This supports migrations in which the semantic declaration is added before GraphQL SDL is
relaxed. Type- and field-level declarations are additive; neither can make a field nullable.

Effective persistence nullability is:

```text
nonNull = SDL non-null
       OR containing type is in semanticNotNull.types
       OR field is in semanticNotNull.fields
```

Primary and generated internal IDs retain their existing mandatory constraints independently of
this policy.

## Loading and model flow

Introduce a strict `PersistenceConfig` loader responsible for the entire YAML document. The loader
produces typed sets of denied types, semantic non-null types and fields, and the existing relationship
settings.

`PersistenceSchemaModelLoader` then:

1. loads the assembled schema and schema source files;
2. loads and structurally validates `persistence.yaml`;
3. discovers persistent types and applies the denylist;
4. resolves and validates semantic non-null coordinates against the selected model;
5. passes the resolved field-coordinate set into `PersistenceModelContext`;
6. builds attributes using effective persistence nullability rather than SDL nullability alone.

All generation, snapshot, diff, and effective-model tasks consume the same typed configuration and
declare the YAML file as an optional input. No task may implement its own interpretation.

## Diagnostics

Errors should include the configuration path, YAML key, and offending coordinate. Examples:

```text
src/main/viaduct/persistence.yaml: denyList.types contains unknown GraphQL type 'AuditEvnt'
```

```text
src/main/viaduct/persistence.yaml: semanticNotNull.fields contains 'Group.members', but that
coordinate is a to-many relationship and has no nullable owning column
```

```text
src/main/viaduct/persistence.yaml: persisted field 'Order.customer' targets denied type 'Customer'
```

## Migration

1. Add `src/main/viaduct/persistence.yaml`.
2. Remove `includedTypeNames` from `viaductPgPersistence`. Types omitted from the old allowlist must be
   added to `denyList.types` if they should remain non-persistent.
3. Move the contents of `persistence-relationships.yaml` under the `relationships` key.
4. Add semantic non-null coordinates only after existing data satisfies the proposed constraints;
   schema diff should expose any required data migration before `NOT NULL` is applied.
5. Remove `relationshipConfigFile` overrides, or replace them with `persistenceConfigFile` when a
   nonconventional path is required.

For one release, encountering `includedTypeNames` or `relationshipConfigFile` should fail with a
targeted migration message rather than being ignored. The old relationship-only YAML filename is not
read implicitly, preventing two files from becoming competing sources of truth.

## Test plan

- YAML loader tests for absent files, valid documents, unknown keys, wrong value types, duplicates,
  and malformed coordinates.
- Discovery tests proving denylist subtraction and rejection of unknown or ineligible types.
- Model tests for field-level and type-level semantic non-null on basic attributes, scalar arrays,
  and to-one relationships.
- Negative tests for denied types, resolver fields, to-many fields, and non-object coordinates.
- Dynamic HBM tests proving semantic non-null produces `not-null="true"` mappings without
  generating entity source code.
- Schema-diff tests proving semantic declarations result in `NOT NULL` changes.
- Gradle functional tests proving YAML edits invalidate tasks and no allowlist remains in the DSL.
- Compatibility tests proving GraphQL SDL and runtime response behavior are unchanged.

## Acceptance criteria

- A project with no YAML file persists all discovered eligible types.
- A type listed in `denyList.types` generates no entity or table mapping.
- Persistence generation fails rather than silently weakening a relationship to a denied type.
- A valid semantic non-null field generates non-null Kotlin and database representations even when
  its GraphQL field is nullable.
- A semantic non-null type applies the same rule to all of its eligible stored fields without
  cascading to related types.
- Invalid or stale coordinates fail with actionable messages.
- Gradle contains only the optional YAML file location; denylist and semantic policy values live in
  YAML.
