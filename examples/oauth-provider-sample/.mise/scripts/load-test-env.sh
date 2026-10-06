#!/usr/bin/env bash
# CI can supply a database service; local tests use the mise dependency tasks.
if [[ -z "${TEST_DATABASE_ADMIN_JDBC_URL:-}" ]]; then
  mise run deps-start
  set -a
  source .local/database.env
  set +a
fi
: "${DATABASE_PASSWORD:?DATABASE_PASSWORD is required}"
export DATABASE_USER="${DATABASE_USER:-postgres}"
