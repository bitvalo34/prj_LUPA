#!/usr/bin/env bash
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
PORT="${PORT:-8081}"
QUEUE_LIMIT="${QUEUE_LIMIT:-16}"

mvn -o -q -DskipTests package
exec java   -Dlupa.e23.experimentalNoCancellation=true   -Dlupa.e23.experimentalPlanQueue="$QUEUE_LIMIT"   -jar target/lupa.jar   --port="$PORT"   --data-root="$ROOT/data"
