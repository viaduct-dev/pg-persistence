#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
mkdir -p .local
if [[ ! -f .local/database.env ]]; then
  python3 - <<'PY'
from pathlib import Path
import secrets
password = secrets.token_hex(24)
Path(".local/database.env").write_text(
    "POSTGRES_PASSWORD=" + password + "\n"
    "POSTGRES_DB=postgres\n"
    "DATABASE_USER=postgres\n"
    "DATABASE_PASSWORD=" + password + "\n"
    "DATABASE_JDBC_URL=jdbc:postgresql://127.0.0.1:56322/oauth_provider_sample\n"
    "TEST_DATABASE_ADMIN_JDBC_URL=jdbc:postgresql://127.0.0.1:56322/postgres\n"
)
PY
fi
if ! podman container exists oauth-sample-postgres; then
  podman run --detach --name oauth-sample-postgres --env-file .local/database.env \
    --publish 127.0.0.1:56322:5432 public.ecr.aws/supabase/postgres:17.6.1.132 >/dev/null
else
  podman start oauth-sample-postgres >/dev/null
fi
for attempt in {1..30}; do
  if podman exec oauth-sample-postgres pg_isready -U postgres >/dev/null 2>&1; then
    # Create an empty application database, owned by the backend role. These are platform settings.
    podman exec -i oauth-sample-postgres psql -U supabase_admin -d postgres -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
SELECT 'CREATE DATABASE oauth_provider_sample OWNER postgres TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'oauth_provider_sample');
\gexec
SQL
    echo "Sample PostgreSQL is ready on 127.0.0.1:56322."
    exit 0
  fi
  sleep 1
done
echo "Sample PostgreSQL did not become ready." >&2
exit 1
