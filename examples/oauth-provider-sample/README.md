# OAuth provider sample

A small application based on [Batteries Included](https://github.com/viaduct-dev/batteries-included)'s React/Vite, Kotlin/Ktor, and Viaduct foundation.
Administrators create users and groups, register OAuth clients, and assign group access rules.
An ordinary user authorizes a client through OAuth authorization-code flow with S256 PKCE.
The bundled client exchanges its code and calls a protected demonstration resource.

## How this sample was created

The starting point was [Batteries Included](https://github.com/viaduct-dev/batteries-included)'s React/Vite frontend and Kotlin/Ktor/Viaduct backend.
The sample keeps that application structure, adds users, groups, client registration, and group
access rules, and implements OAuth authorization-code flow in Kotlin. The management page uses
one GraphQL operation assembled from client fragments.

The backend is split into two Viaduct tenants and a shared Kotlin module:

| Module | Responsibility |
|---|---|
| `database` | Persistent nodes, selective node resolvers, management queries and mutations |
| `provider` | Computed access decisions and OAuth request validation; owns no persistent nodes |
| `common` | Small storage interface and shared value types; not a Viaduct tenant |

Only `database` applies the pg-persistence plugin. Accounts are ordinary schema-defined records;
Kotlin hashes passwords using salted PBKDF2-SHA256. This replaces [Batteries Included](https://github.com/viaduct-dev/batteries-included)'s Supabase
Auth dependency and handwritten auth migrations. Administrator creation is bootstrap data.
Every application table and constraint comes from GraphQL SDL.

[`backend/settings.gradle.kts`](backend/settings.gradle.kts) registers the two tenants and includes
this repository as a Gradle composite build. Both the plugin and runtime are built from source;
no separately published pg-persistence snapshot is required. Set `PG_PERSISTENCE_SOURCE` only
when testing a different source checkout.

## Run locally

Install [mise](https://mise.jdx.dev/getting-started.html), then run these commands from the repository root.
Mise manages Java 21, Node 22, Python 3.12, and Podman:

```bash
cd examples/oauth-provider-sample
mise trust
mise install
mise run dev
```

Open **http://localhost:10000**. The administrator username is `admin`; its randomly generated
password is in `.local/admin-password`. Local credentials are ignored by Git.

The `dev` task starts Podman and a dedicated PostgreSQL container on **127.0.0.1:56322**, installs
frontend dependencies, builds the frontend,
installs the generated application schema into a fresh database, and starts the backend.
It does not reset Gateloom's or [Batteries Included](https://github.com/viaduct-dev/batteries-included)'s databases.
Subsequent runs retain application data. Development signing keys are regenerated on server
restart, invalidating old sessions and access tokens.
Press Ctrl+C to stop the server; the database container remains running.

The task graph in [`mise.toml`](mise.toml) coordinates dependency setup, npm, and Gradle:

| Command | Purpose |
|---|---|
| `mise run deps-start` | Start Podman and the sample database |
| `mise run dev` | Start dependencies and run the complete application |
| `mise run build` | Build the frontend and backend distribution |
| `mise run check` | Run backend tests and frontend tests, lint, typecheck, and build |
| `mise run verify-schema` | Regenerate persistence artifacts and test an empty database |
| `mise run status` | Show the sample database container |
| `mise run stop` | Stop that container while preserving its data |

The dependency tasks use small helpers under `.mise/scripts`; there is no separate `run.sh` entry
point. Each checkout keeps its credentials in `.local`. For another local checkout, use a separate
container and database port:

```bash
OAUTH_SAMPLE_CONTAINER_NAME=oauth-sample-other OAUTH_SAMPLE_DATABASE_PORT=56422 mise run dev
```

The selected container and port are saved in `.local/database.env` and reused by subsequent
commands. Only one server can listen on port 10000 at a time.

To demonstrate OAuth:

1. Create an ordinary user and a group; add that user to the group.
2. Register a client with redirect URL `http://localhost:10000/demo-client` and scope `demo:read`.
3. Create an access rule granting that group `demo:read` for the client.
4. Open **Demo client** and paste the registered client ID.
5. Sign in as the ordinary user and approve the request.
6. The client exchanges the code and displays the protected resource's response.

Removing the membership, deleting the access rule, or disabling the client prevents subsequent
authorization. The token endpoint checks current access again before redeeming an existing grant.
Issued access tokens expire after five minutes; rule changes do not revoke an already-issued JWT.

## Creating the GraphQL schema

Define persisted types in
[`backend/database/src/main/viaduct/schema/Database.graphqls`](backend/database/src/main/viaduct/schema/Database.graphqls).
The database module applies Kotlin, KSP, the Viaduct module plugin, and `dev.viaduct.pg-persistence`
in its [build file](backend/database/build.gradle.kts). The provider module applies the Viaduct
module plugin without pg-persistence.

For example, these declarations define accounts, groups, and their memberships:

```graphql
type Account implements Node @scope(to: ["admin", "internal"])
  @pgUnique(fields: ["username"]) {
  id: ID!
  username: String!
  admin: Boolean!
}

extend type Account @scope(to: ["internal"]) {
  passwordHash: String!
}

type Group implements Node @scope(to: ["admin", "internal"])
  @pgUnique(fields: ["name"]) {
  id: ID!
  name: String!
  members: [Membership!]!
}

type Membership implements Node @scope(to: ["admin", "internal"])
  @pgUnique(fields: ["accountId", "groupId"]) {
  id: ID!
  accountId: ID! @idOf(type: "Account")
  groupId: ID! @idOf(type: "Group")
}
```

`Node` types become persistent entities. Non-null scalar fields become required columns;
`@idOf` declares references, and `@pgUnique` defines single-column or composite uniqueness.
`Group.members` is resolved through the generated persistent relationship. Password hashes
belong to an `internal` type extension so they are excluded from the public and admin schemas.

Declare the custom directive in the application schemabase,
[`backend/src/main/viaduct/schemabase/Persistence.graphqls`](backend/src/main/viaduct/schemabase/Persistence.graphqls),
so Viaduct can parse it:

```graphql
directive @pgUnique(fields: [String!]!) repeatable on OBJECT
```

Declare management fields with `@resolver` in the database tenant SDL:

```graphql
extend type Query @scope(to: ["admin"]) {
  accounts: [Account!]! @resolver
  groups: [Group!]! @resolver
}

extend type Mutation @scope(to: ["admin"]) {
  createGroup(name: String!): Group! @resolver
}
```

Viaduct generates typed resolver bases from those fields. Implement them in
[`Resolvers.kt`](backend/database/src/main/kotlin/com/example/database/Resolvers.kt), including
authorization and input validation. Implement node fetching with an annotated Kotlin class,
for example:

```kotlin
@Resolver
class AccountNode(private val db: DbClient) : NodeResolvers.Account() {
    override suspend fun resolve(ctx: Context): Account = withContext(Dispatchers.IO) {
        ctx.principal().requireAdmin()
        db.fetchByInternalId(ctx, "accountCollection", ctx.id.internalID)
    }
}
```

Node types omit SDL `@resolver`: pg-persistence discovers the Kotlin `@Resolver` implementations
and generates selective node declarations. Query, mutation, and computed fields still use SDL
`@resolver`. The plugin generates persistence metadata and SQL; the business logic remains in Kotlin.

The provider tenant declares its computed query in
[`Provider.graphqls`](backend/provider/src/main/viaduct/schema/Provider.graphqls):

```graphql
type AccessDecision @scope(to: ["default", "admin", "internal"]) {
  allowed: Boolean!
  scopes: [String!]!
}

extend type Query @scope(to: ["default", "admin", "internal"]) {
  accessDecision(clientId: ID! @idOf(type: "OAuthClient"), scopes: [String!]!): AccessDecision! @resolver
}
```

Its resolver calls the shared access policy; `AccessDecision` is computed and has no database table.
[`Sample.kt`](backend/src/main/kotlin/com/example/Sample.kt) injects `DbClient`, `Store`, and
`AccessPolicy` into the resolver classes and configures the public and admin schema scopes.
[`PgStore.kt`](backend/database/src/main/kotlin/com/example/database/PgStore.kt) implements reads
and writes through pg-persistence's collection and mutation APIs over JDBC.

After editing either tenant's SDL, generate the Viaduct types and compile the implementations:

```bash
cd backend # from examples/oauth-provider-sample
mise exec -- ./gradlew :database:classes :provider:classes
```

## Generating SQL and Liquibase files

Run the commands in this section from `examples/oauth-provider-sample/backend`.

### Fresh-install SQL

All six application tables, columns, primary keys, foreign keys, unique constraints, scalar arrays,
and pg_graphql metadata come from
`backend/database/src/main/viaduct/schema/Database.graphqls`.
There is no application migration directory, replacement Hibernate mapping, or persistence YAML.

```bash
mise exec -- ./gradlew :database:buildViaductEffectiveModel
```

The complete fresh-database script is generated at:

```text
database/build/generated/viaduct-effective-model/META-INF/schema-create.sql
```

The same directory contains `hibernate-create.sql` and the individual PostgreSQL and pg_graphql
overlay scripts. `schema-create.sql` combines them in installation order. Leave generated files
unchanged; edit the SDL and regenerate instead.

`mise run dev` runs the Gradle `installSchema` task on first startup. For a manual fresh installation,
load the local database environment and run the installer:

```bash
mise run deps-start
set -a
source ../.local/database.env
set +a
mise exec -- ./gradlew installSchema
```

Use either the automatic first startup or this manual installation on an empty database. The
installer applies `schema-create.sql` unchanged in a transaction and refuses a database with
existing application tables. It also installs the pg_graphql extension as a platform prerequisite.
After a manual install, create the startup marker with `touch ../.local/schema-installed` so
`mise run dev` does not attempt to install the schema again.

### Liquibase snapshots and migration review

Liquibase produces review files for the generated Hibernate model. The fresh installer uses the
SQL above and does not initialize Liquibase or use a Liquibase changelog.

To generate a model snapshot without connecting to a target database:

```bash
mise exec -- ./gradlew :database:hibernateSchemaSnapshot
```

Output: `database/build/schema-diff/hibernate-snapshot.json`.

To compare the model with an existing PostgreSQL database, the database module's build file wires
the diff task to these environment variables:

```kotlin
viaductPgPersistence {
    schemaDiffUrl.set(providers.environmentVariable("SCHEMA_DIFF_DATABASE_URL"))
    schemaDiffUser.set(providers.environmentVariable("SCHEMA_DIFF_DATABASE_USER"))
    schemaDiffPassword.set(providers.environmentVariable("SCHEMA_DIFF_DATABASE_PASSWORD"))
}
```

For the local database created by `mise run dev` or `mise run deps-start`:

```bash
set -a
source ../.local/database.env
set +a
export SCHEMA_DIFF_DATABASE_URL="$DATABASE_JDBC_URL"
export SCHEMA_DIFF_DATABASE_USER="$DATABASE_USER"
export SCHEMA_DIFF_DATABASE_PASSWORD="$DATABASE_PASSWORD"
mise exec -- ./gradlew :database:hibernateSchemaDiff
```

For another database, set those three `SCHEMA_DIFF_DATABASE_*` variables to its JDBC URL and
credentials. The task generates these files without applying changes:

| File under `database/build/schema-diff/` | Purpose |
|---|---|
| `hibernate-raw-review.postgresql.sql` | Full Liquibase diff |
| `hibernate-review.postgresql.sql` | Additions accepted by the conservative SQL allowlist |
| `hibernate-destructive-review.postgresql.sql` | Other changes requiring explicit review |

Review both split SQL files before preparing an upgrade. The Hibernate diff covers the physical
model; also review the generated PostgreSQL and pg_graphql overlays. Fresh-install
`schema-create.sql` is not an upgrade migration, and no task automatically applies the review files.

## Database access

The backend uses pg-persistence's JDBC transport and a trusted database connection. Application
authorization runs in the backend; the database owner bypasses the generated RLS settings.
There is no browser-facing pg_graphql endpoint, and this sample does not claim database RLS policies
enforce its access rules.

## OAuth behavior

- Public clients use authorization-code flow with **S256 PKCE**.
- Redirect URLs must match a registered URL exactly. HTTPS is required except on loopback hosts.
- Grants expire after one minute, store only code hashes, and are consumed by an atomic database delete.
- Login sessions and OAuth access tokens have different issuers and audiences.
- Access tokens use RS256, audience `<issuer>/demo-resource`, and the approved `scope` claim.
- `/demo-resource` verifies the signature, issuer, audience, expiration, token type, and `demo:read` scope.
- The client checks `state` and never redeems a mismatched callback.
- Token and session responses use `Cache-Control: no-store`.

Endpoints: `/oauth/authorize`, `/oauth/token`, `/oauth/jwks`, and
`/.well-known/oauth-authorization-server`. This is OAuth, not OpenID Connect: it issues no ID tokens.
The first version has no refresh tokens. Expired, unredeemed grants remain stored; consumed grants are deleted.

For a configured issuer, set `ISSUER_URL`, `DATABASE_JDBC_URL`, `DATABASE_USER`,
`DATABASE_PASSWORD`, `ADMIN_USERNAME`, and `ADMIN_PASSWORD`.
Stable signing keys use `SIGNING_PRIVATE_KEY_DER` (base64 PKCS#8) and
`SIGNING_PUBLIC_KEY_DER` (base64 X.509). Ephemeral keys require explicit `DEMO_MODE=true`.

## Verification

Run these commands from `examples/oauth-provider-sample`:

```bash
mise run check
```

Backend integration tests create an empty, isolated PostgreSQL database from `template0` and install
**only the generated application SQL**. They exercise both Viaduct tenants, database constraints, authenticated
HTTP OAuth requests, denied access, exact redirect and client binding, expiration, and concurrent
redemption with exactly one winner.
Frontend tests cover the RFC PKCE example, state rejection, and single redemption under React StrictMode.

To verify schema generation without any existing initialization or Liquibase history:

```bash
mise run verify-schema
```

This task saves hashes of every generated persistence artifact, moves the generated outputs out
of the build directory, regenerates from SDL with the build cache disabled, and runs all backend
tests in a new empty database. It verifies that the regenerated artifacts match byte for byte.
Fresh installation does not initialize or run Liquibase; the installer applies `schema-create.sql`
unchanged. The optional Liquibase tasks above only generate review files. The pg_graphql extension
is installed as a platform prerequisite.

Local test tasks start the sample database through mise. CI sets `TEST_DATABASE_ADMIN_JDBC_URL`,
`DATABASE_USER`, and `DATABASE_PASSWORD` to its supplied PostgreSQL service, so the same tasks run
without starting Podman or touching a local database.

## pg-persistence capabilities demonstrated

This sample exercises:

- SDL `@pgUnique(fields: [...])` for single-column and composite unique constraints.
- Complete `schema-create.sql` output from the existing effective-model task.
- PostgreSQL scalar-array column types and unbounded `text` columns for GraphQL strings.
- A `PgGraphqlClient(PgGraphqlExecutor)` constructor so JDBC uses the same collection and mutation APIs as HTTP.

These capabilities are implemented by pg-persistence. The sample contains no application SQL workaround.
