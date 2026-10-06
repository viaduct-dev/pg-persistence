#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
umask 077
mkdir -p .local
if [[ -f .local/database.env ]]; then
  set -a
  source .local/database.env
  set +a
fi
export OAUTH_SAMPLE_CONTAINER_NAME="${OAUTH_SAMPLE_CONTAINER_NAME:-oauth-sample-postgres}"
export OAUTH_SAMPLE_DATABASE_PORT="${OAUTH_SAMPLE_DATABASE_PORT:-56322}"
if [[ ! -f .local/database.env ]]; then
  if podman container exists "$OAUTH_SAMPLE_CONTAINER_NAME"; then
    echo "The sample container already exists, but this checkout has no matching credentials." >&2
    echo "Set OAUTH_SAMPLE_CONTAINER_NAME and OAUTH_SAMPLE_DATABASE_PORT to use a separate instance." >&2
    exit 1
  fi
  python3 - <<'PY'
from pathlib import Path
import os
import re
import secrets
password = secrets.token_hex(24)
port = int(os.environ["OAUTH_SAMPLE_DATABASE_PORT"])
assert 1 <= port <= 65535, "Invalid database port"
container = os.environ["OAUTH_SAMPLE_CONTAINER_NAME"]
assert re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]*", container), "Invalid container name"
Path(".local/database.env").write_text(
    f"OAUTH_SAMPLE_CONTAINER_NAME={container}\n"
    f"OAUTH_SAMPLE_DATABASE_PORT={port}\n"
    "POSTGRES_PASSWORD=" + password + "\n"
    "POSTGRES_DB=postgres\n"
    "DATABASE_USER=postgres\n"
    "DATABASE_PASSWORD=" + password + "\n"
    f"DATABASE_JDBC_URL=jdbc:postgresql://127.0.0.1:{port}/oauth_provider_sample\n"
    f"TEST_DATABASE_ADMIN_JDBC_URL=jdbc:postgresql://127.0.0.1:{port}/postgres\n"
)
PY
fi
set -a
source .local/database.env
set +a
if ! podman container exists "$OAUTH_SAMPLE_CONTAINER_NAME"; then
  podman run --detach --name "$OAUTH_SAMPLE_CONTAINER_NAME" --env-file .local/database.env \
    --publish "127.0.0.1:$OAUTH_SAMPLE_DATABASE_PORT:5432" public.ecr.aws/supabase/postgres:17.6.1.132 >/dev/null
else
  podman start "$OAUTH_SAMPLE_CONTAINER_NAME" >/dev/null
fi
for attempt in {1..30}; do
  if podman exec "$OAUTH_SAMPLE_CONTAINER_NAME" pg_isready -U postgres >/dev/null 2>&1; then
    podman exec -e "PGPASSWORD=$DATABASE_PASSWORD" "$OAUTH_SAMPLE_CONTAINER_NAME" \
      psql -h 127.0.0.1 -U "$DATABASE_USER" -d postgres -v ON_ERROR_STOP=1 -c 'SELECT 1' >/dev/null
    # Create an empty application database, owned by the backend role. These are platform settings.
    podman exec -i "$OAUTH_SAMPLE_CONTAINER_NAME" psql -U supabase_admin -d postgres -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
SELECT 'CREATE DATABASE oauth_provider_sample OWNER postgres TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'oauth_provider_sample');
\gexec
SQL
    echo "Sample PostgreSQL is ready on 127.0.0.1:$OAUTH_SAMPLE_DATABASE_PORT."
    exit 0
  fi
  sleep 1
done
echo "Sample PostgreSQL did not become ready." >&2
exit 1
