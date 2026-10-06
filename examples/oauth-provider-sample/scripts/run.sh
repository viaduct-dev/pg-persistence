#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/database.sh
umask 077
if [[ ! -f .local/admin-password ]]; then
  python3 - <<'PY'
from pathlib import Path
import secrets
Path(".local/admin-password").write_text(secrets.token_urlsafe(24))
PY
fi
set -a
source .local/database.env
set +a
export ADMIN_PASSWORD
ADMIN_PASSWORD="$(cat .local/admin-password)"
export DEMO_MODE=true
export ISSUER_URL=http://localhost:10000
npm run build
if [[ ! -f .local/schema-installed ]]; then
  (cd backend && ./gradlew installSchema)
  touch .local/schema-installed
fi
echo "Open http://localhost:10000. Administrator username: admin."
echo "The administrator password is in .local/admin-password."
cd backend
./gradlew installDist
exec ./build/install/oauth-provider-sample/bin/oauth-provider-sample
