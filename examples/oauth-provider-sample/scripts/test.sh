#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/database.sh
set -a
source .local/database.env
set +a
cd backend
./gradlew test
