#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${TEST_DATABASE_ADMIN_JDBC_URL:-}" ]]; then
  ./scripts/database.sh
  set -a
  source .local/database.env
  set +a
fi
python3 - <<'PY'
from pathlib import Path
import hashlib
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

backend = Path("backend").resolve()
generated = backend / "database/build/generated"
directories = ("viaduct-persistence", "viaduct-effective-model")
tasks = [":database:generateViaductPgPersistenceModel", "--rerun",
         ":database:buildViaductEffectiveModel", "--rerun"]

def hashes():
    return {str(path.relative_to(generated)): hashlib.sha256(path.read_bytes()).hexdigest()
            for name in directories for path in (generated / name).rglob("*") if path.is_file()}

subprocess.run(["./gradlew", *tasks, "--no-build-cache"], cwd=backend, check=True)
before = hashes()
assert before, "Generation produced no persistence artifacts"
with tempfile.TemporaryDirectory(prefix="oauth-schema-verification-") as backup:
    for name in directories:
        shutil.move(str(generated / name), str(Path(backup) / name))
    subprocess.run(["./gradlew", *tasks, ":test", "--rerun", "--no-build-cache"],
                   cwd=backend, check=True)
    after = hashes()
    changed = sorted(path for path in before.keys() | after.keys() if before.get(path) != after.get(path))
    assert not changed, "Generated artifacts differ: " + ", ".join(changed)

reports = list((backend / "build/test-results/test").glob("TEST-*.xml"))
assert reports, "Backend tests produced no reports"
totals = {key: sum(int(ET.parse(report).getroot().get(key, "0")) for report in reports)
          for key in ("tests", "failures", "errors", "skipped")}
assert totals["tests"] > 0 and all(totals[key] == 0 for key in ("failures", "errors", "skipped")), totals
print(f"Verified {len(before)} identical generated artifacts; fresh-database tests: {totals}")
PY
