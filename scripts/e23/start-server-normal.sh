#!/usr/bin/env bash
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
PORT="${PORT:-8081}"

mvn -o -q -DskipTests package
exec java -jar target/lupa.jar   --port="$PORT"   --data-root="$ROOT/data"
