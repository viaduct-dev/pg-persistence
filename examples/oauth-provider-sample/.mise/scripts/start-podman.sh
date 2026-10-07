#!/usr/bin/env bash
set -euo pipefail
if podman info >/dev/null 2>&1; then
  exit 0
fi
if [[ "$(uname -s)" == "Darwin" ]]; then
  if [[ -z "$(podman machine list --format '{{.Name}}')" ]]; then
    podman machine init
  fi
  podman machine start
fi
podman info >/dev/null
