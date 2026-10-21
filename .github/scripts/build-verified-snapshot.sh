#!/usr/bin/env bash
set -euo pipefail

# Preserve evidence if a test worker stalls; do not bypass the build gate.
./gradlew clean build "-PreleaseVersion=$VERSION" --no-parallel --no-daemon --max-workers=3 --info &
build_pid=$!
monitor() {
    while sleep 120; do
        if ! kill -0 "$build_pid" 2>/dev/null; then return; fi
        echo '::group::Build progress diagnostics'
        jcmd -l
        while read -r pid main rest; do
            case "$main" in
                worker.org.gradle.process.internal.worker.GradleWorkerMain)
                    jcmd "$pid" Thread.print -l || true
                    ;;
            esac
        done < <(jcmd -l)
        if [ -n "${TEST_POSTGRES_CONTAINER:-}" ]; then
            docker exec "$TEST_POSTGRES_CONTAINER" psql -U postgres -d postgres -c \
                "SELECT datname, pid, state, wait_event_type, wait_event, left(query, 300) AS query FROM pg_stat_activity WHERE datname LIKE 'kan%' ORDER BY query_start;" || true
        fi
        echo '::endgroup::'
    done
}
monitor &
monitor_pid=$!
trap 'kill "$monitor_pid" 2>/dev/null || true' EXIT
wait "$build_pid"
