# OAuth provider sample

A small application based on Batteries Included's React/Vite, Kotlin/Ktor, and Viaduct foundation.
Administrators create users and groups, register OAuth clients, and assign group access rules.
An ordinary user authorizes a client through OAuth authorization-code flow with S256 PKCE.
The bundled client exchanges its code and calls a protected demonstration resource.

## Run locally

Requires Java 21, Node, Python 3, and a running Podman machine.

Gradle builds the pg-persistence plugin and runtime from this repository through a composite build.
No separately published pg-persistence snapshot is required. Set `PG_PERSISTENCE_SOURCE` only
when testing a different source checkout.

```bash
npm ci
./scripts/run.sh
```

Open **http://localhost:10000**. The administrator username is `admin`; its randomly generated
password is in `.local/admin-password`. Local credentials are ignored by Git.

The script starts a dedicated PostgreSQL container on **127.0.0.1:56322**, builds the frontend,
installs the generated application schema into a fresh database, and starts the backend.
It does not reset Gateloom's or Batteries Included's databases.
Subsequent runs retain application data. Development signing keys are regenerated on server
restart, invalidating old sessions and access tokens.

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

## Schema-only database creation

All six application tables, columns, primary keys, foreign keys, unique constraints, scalar arrays,
and pg_graphql metadata come from
`backend/database/src/main/viaduct/schema/Database.graphqls`.
The application-level `@pgUnique` declaration is in
`backend/src/main/viaduct/schemabase/Persistence.graphqls`.
There is no application migration directory, replacement Hibernate mapping, or persistence YAML.

```bash
cd backend
./gradlew :database:buildViaductEffectiveModel
```

The complete fresh-database script is generated at:

```text
database/build/generated/viaduct-effective-model/META-INF/schema-create.sql
```

`installSchema` applies that output transactionally and refuses a database with existing application
tables. PostgreSQL and the pg_graphql extension are platform prerequisites. Bootstrap account creation
is application data, not additional database structure. The generated SQL is for fresh installation;
existing databases still need a reviewed migration workflow.

Accounts are schema-defined application records. Kotlin hashes passwords using salted PBKDF2-SHA256;
the sample does not need Batteries Included's Supabase Auth tables or handwritten auth migrations.

## Two Viaduct tenants

| Module | Responsibility |
|---|---|
| `database` | Persistent nodes, selective node resolvers, management queries and mutations |
| `provider` | Computed access decisions and OAuth request validation; owns no persistent nodes |
| `common` | Small storage interface and shared value types; not a Viaduct tenant |

Only `database` applies the pg-persistence plugin. The management page uses one GraphQL operation
with client fragments. Group memberships resolve through generated persistent relationships.
The consent page calls the provider tenant's computed `accessDecision` field.
The database node SDL omits `@resolver`; the plugin derives selective node declarations from
the annotated Kotlin node implementations.

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

```bash
./scripts/test.sh
npm test
npm run typecheck
npm run lint
npm run build
```

Backend integration tests create an empty, isolated PostgreSQL database from `template0` and install
**only the generated application SQL**. They exercise both Viaduct tenants, database constraints, authenticated
HTTP OAuth requests, denied access, exact redirect and client binding, expiration, and concurrent
redemption with exactly one winner.
Frontend tests cover the RFC PKCE example, state rejection, and single redemption under React StrictMode.

To verify schema generation without any existing initialization or Liquibase history:

```bash
./scripts/verify-schema.sh
```

This script saves hashes of every generated persistence artifact, moves the generated outputs out
of the build directory, regenerates from SDL with the build cache disabled, and runs all backend
tests in a new empty database. It verifies that the regenerated artifacts match byte for byte.
The sample does not initialize or run Liquibase; its fresh installer applies `schema-create.sql`
unchanged. The pg_graphql extension is installed as a platform prerequisite.

## pg-persistence capabilities demonstrated

This sample exercises:

- SDL `@pgUnique(fields: [...])` for single-column and composite unique constraints.
- Complete `schema-create.sql` output from the existing effective-model task.
- PostgreSQL scalar-array column types and unbounded `text` columns for GraphQL strings.
- A `PgGraphqlClient(PgGraphqlExecutor)` constructor so JDBC uses the same collection and mutation APIs as HTTP.

These capabilities are implemented by pg-persistence. The sample contains no application SQL workaround.
