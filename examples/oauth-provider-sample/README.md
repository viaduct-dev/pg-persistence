# OAuth provider sample

Try a small OAuth provider built with Viaduct and pg-persistence. You'll create a user,
give them access to a demo client, and watch the client sign in and call a protected resource.

The app starts from [Batteries Included](https://github.com/viaduct-dev/batteries-included)'s
React frontend and Kotlin/Viaduct backend. This version uses pg-persistence and a local
PostgreSQL database instead of Supabase Auth, so you don't need a Supabase account to try it.

## Start the app

Install [mise](https://mise.jdx.dev/getting-started.html), then clone this repository:

```bash
git clone --branch feat/oauth-provider-sample https://github.com/viaduct-dev/pg-persistence.git
cd pg-persistence/examples/oauth-provider-sample
mise trust
mise install
mise run dev
```

Already have the repository checked out? Start with `cd examples/oauth-provider-sample`
from its root.

Mise installs the tools, starts a dedicated local PostgreSQL database, creates the initial
schema, and builds and runs the app. The first run may take a few minutes.

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
For example, a group is declared like this:

```graphql
type Group implements Node @scope(to: ["admin", "internal"])
  @requiresAdmin @pgUnique(fields: ["name"]) {
  id: ID!
  name: String!
  members: [Membership!]!
}
```

This defines the group's stored fields and makes its name unique. pg-persistence generates
the database structure from the schema. `@requiresAdmin` tells Viaduct's access checker to
require an administrator; the Kotlin resolvers provide application behavior.

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
