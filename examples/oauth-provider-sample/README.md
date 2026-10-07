# OAuth provider sample

Try a small OAuth provider built with Viaduct and pg-persistence. You'll create a user,
give them access to a demo client, and watch the client sign in and call a protected resource.

The app starts from [Batteries Included](https://github.com/viaduct-dev/batteries-included)'s
React frontend and Kotlin/Viaduct backend. This version uses pg-persistence and a local
PostgreSQL database instead of Supabase Auth, so you don't need a Supabase account to try it.

## Get started with pg-persistence

Follow the schema-to-database workflow before starting the app: define persisted GraphQL types,
generate PostgreSQL SQL, inspect the result, and install it through the sample's startup task.
The schema and resolver implementations are already checked in, so you can run these steps
without recreating them. Use the same workflow when adding persistence to your own application.

### 1. Prepare the sample

Install [mise](https://mise.jdx.dev/getting-started.html), then clone this repository:

```bash
git clone --branch feat/oauth-provider-sample https://github.com/viaduct-dev/pg-persistence.git
cd pg-persistence/examples/oauth-provider-sample
mise trust
mise install
```

Already have the repository checked out? Start with `cd examples/oauth-provider-sample`
from its root.

### 2. Add persisted types to the GraphQL schema

Apply pg-persistence to the Viaduct tenant that owns database nodes. The sample's
[`backend/database/build.gradle.kts`](backend/database/build.gradle.kts) adds it alongside
the existing Kotlin, KSP, and Viaduct module plugins:

```kotlin
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.viaduct.module)
    id("dev.viaduct.pg-persistence")
}
```

The sample builds the plugin and runtime from this repository through the composite build in
[`backend/settings.gradle.kts`](backend/settings.gradle.kts). The `provider` tenant does not
apply pg-persistence: its access decisions are computed rather than stored.

Add persisted types to
[`backend/database/src/main/viaduct/schema/Database.graphqls`](backend/database/src/main/viaduct/schema/Database.graphqls).
For example, these existing declarations define groups and memberships:

```graphql
type Group implements Node @scope(to: ["admin", "internal"])
  @requiresAdmin @pgUnique(fields: ["name"]) {
  id: ID!
  name: String!
  members: [Membership!]!
}

type Membership implements Node @scope(to: ["admin", "internal"])
  @requiresAdmin @pgUnique(fields: ["accountId", "groupId"]) {
  id: ID!
  accountId: ID! @idOf(type: "Account")
  groupId: ID! @idOf(type: "Group")
}
```

`Node` types become persisted entities. `String!` creates a required text column;
`@pgUnique` creates a unique constraint, and `@idOf` declares a reference to another entity.
The full schema also defines `Account`, `OAuthClient`, `AccessRule`, and `AuthorizationGrant`.
`Group.members` describes the membership relationship rather than a column on `groups`.

`@requiresAdmin` is the sample's application authorization directive. Its definition is in
[`Authorization.graphqls`](backend/src/main/viaduct/schemabase/Authorization.graphqls), and
[`AdminChecker.kt`](backend/src/main/kotlin/com/example/AdminChecker.kt) checks the signed-in user.
The database module's `centralSchemaDirectory` setting makes these application directives
available to pg-persistence through the assembled Viaduct schema.

### 3. Generate and inspect the PostgreSQL schema

After adding or editing the SDL, run the generation task from the backend directory:

```bash
cd backend
mise exec -- ./gradlew :database:buildViaductEffectiveModel
```

The task builds the tenant and derives the database model from the assembled GraphQL schema.
It generates SQL without connecting to PostgreSQL. Inspect the complete fresh-database script:

```bash
cat database/build/generated/viaduct-effective-model/META-INF/schema-create.sql
```

For example, the `Group` declaration above produces this table definition, formatted here
for readability:

```sql
create table public.groups (
    _uuid_id uuid default gen_random_uuid() not null,
    id text not null,
    name text not null,
    primary key (_uuid_id),
    unique (name)
);
```

The generated membership table contains `account_id` and `group_id` UUID columns, foreign keys
to the account and group tables, and `unique (account_id, group_id)`.
The complete output creates all six application tables, their constraints, and pg_graphql metadata.
The same directory contains `hibernate-create.sql` and the individual PostgreSQL and pg_graphql
overlay files combined by `schema-create.sql`.

Leave generated SQL unchanged. Edit the GraphQL definitions and rerun the task to change the model.
Kotlin resolvers still provide application behavior; pg-persistence generates the database
structure and supports their reads and writes.

### 4. Install the generated schema and start the app

Return to the sample directory and run it:

```bash
cd ..
mise run dev
```

Mise starts a dedicated local PostgreSQL database, builds the app, and runs the Gradle
`installSchema` task on first startup. That installer applies the generated `schema-create.sql`
unchanged to an empty application database; no handwritten application SQL or Liquibase
initialization is required. Database creation and installation of the pg_graphql extension
are platform setup. The first run may take a few minutes.

Subsequent starts keep the existing data. For schema changes to an existing database, use the
[migration review workflow](../../README.md#compare-with-an-existing-database) rather than
reapplying the fresh-install script.

Open **http://localhost:10000** and sign in as `admin`. You'll find the generated administrator
password in `.local/admin-password`. That file is kept out of Git.

## Try the OAuth flow

First, use the administrator page to set up access:

1. Create an ordinary user with a username and password.
2. Create a group and add that user to it.
3. Register a client with redirect URL `http://localhost:10000/demo-client` and scope `demo:read`.
   Keep its client ID handy.
4. Create an access rule granting that group `demo:read` for the client.

Now try it as the user:

1. Open **Demo client** and enter the client ID.
2. Sign in with the ordinary user's credentials.
3. Approve the request.

The demo client exchanges the authorization code for an access token and shows the
protected resource's response. You've now completed the OAuth authorization-code flow
with PKCE.

To try a denied request, remove the user's group membership and start a new authorization.
An access token you've already issued remains valid until it expires, after five minutes.

## Find your way around

The sample separates stored data from access decisions:

- [`database`](backend/database/) defines accounts, groups, clients, and access rules,
  and uses pg-persistence to read and write them.
- [`provider`](backend/provider/) decides whether a user can authorize a client.
- [`common`](backend/common/) contains the small interfaces and value types shared by both.

Start with [`Database.graphqls`](backend/database/src/main/viaduct/schema/Database.graphqls).
Next, look at [`Resolvers.kt`](backend/database/src/main/kotlin/com/example/database/Resolvers.kt)
for the management queries and mutations, or
[`Provider.graphqls`](backend/provider/src/main/viaduct/schema/Provider.graphqls) for the
computed access decision. [`Sample.kt`](backend/src/main/kotlin/com/example/Sample.kt)
wires the application together.

For the persistence API, schema rules, generated SQL, and migration workflow, see the
[pg-persistence README](../../README.md). For the starter application's foundation, see the
[Batteries Included README](https://github.com/viaduct-dev/batteries-included/blob/main/README.md).

## Keep experimenting

Run these commands from `examples/oauth-provider-sample`:

| Command | What it does |
|---|---|
| `mise run dev` | Build and start the app |
| `mise run check` | Run backend and frontend checks |
| `mise run verify-schema` | Regenerate persistence artifacts and test a fresh database |
| `mise run status` | Show the local database container |
| `mise run stop` | Stop the database without deleting your data |

Press Ctrl+C to stop the app. The database stays running, and your users, groups, and clients
are kept for the next run. The sample uses its own database on `127.0.0.1:56322`.

Restarting the app changes its development signing keys, so you'll need to sign in again
and obtain a new access token.

## Before using this beyond your laptop

This is a learning sample, not a production deployment guide. The database connection belongs
to the trusted backend, and access checks happen there—not through database row-level security.
Keep database credentials and signing keys out of the frontend and Git.

The demo implements OAuth, not OpenID Connect, and does not issue ID or refresh tokens.
For deployment, use a stable issuer and signing keys and review the authentication and access
controls for your application.
