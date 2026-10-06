# AGENTS.md

This checkout is a small OAuth provider sample based on Batteries Included.
The current architecture and commands are in README.md.

- Preserve the two Viaduct tenants: database (pg-persistence plugin) and provider (no plugin).
- Generate every application database definition from GraphQL SDL. Do not add application SQL migrations.
- Use pg-persistence for database access. The JDBC connection belongs only to the trusted backend.
- Keep scope to users, groups, client registration, group access rules, and authorization-code OAuth with S256 PKCE.
- Run scripts/test.sh against the dedicated sample database, plus frontend tests, typecheck, lint, and build.
- Local secrets belong in ignored .local files or environment variables.
