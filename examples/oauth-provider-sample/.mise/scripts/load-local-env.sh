#!/usr/bin/env bash
# Sourced by the backend task from backend/.
set -a
source ../.local/database.env
set +a
umask 077
if [[ ! -f ../.local/admin-password ]]; then
  python3 - <<'PY'
from pathlib import Path
import os
import secrets
Path("../.local/admin-password").write_text(os.environ.get("ADMIN_PASSWORD") or secrets.token_urlsafe(24))
PY
fi
export ADMIN_PASSWORD="${ADMIN_PASSWORD:-$(cat ../.local/admin-password)}"
export ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
export DEMO_MODE="${DEMO_MODE:-true}"
export ISSUER_URL="${ISSUER_URL:-http://localhost:10000}"
