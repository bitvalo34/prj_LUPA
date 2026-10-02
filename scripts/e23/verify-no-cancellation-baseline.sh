#!/usr/bin/env bash
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

mvn -o   -Dtest='LupaSessionTest'   test

echo
echo "E23_NO_CANCELLATION_BASELINE_TESTS SUCCESS"
