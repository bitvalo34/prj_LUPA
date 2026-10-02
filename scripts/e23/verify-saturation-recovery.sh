#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

echo "========== E23 SATURATION / RECOVERY BATTERY =========="
echo "commit=$(git rev-parse HEAD)"
echo "branch=$(git branch --show-current)"
echo "java=$(java -version 2>&1 | head -n 1)"
echo "maven=$(mvn -version | head -n 1)"
echo

TESTS='ServerConfigTest,NioHttpServerAdmissionTest,NioHttpServerSlowWebSocketTest,SessionAdmissionTest,LupaSessionAdmissionTest,LupaSessionFairnessTest,StorageExecutorsTest,TileReadAdmissionTest,TransientBufferBudgetTest,LupaSessionReconnectCleanupTest,LupaSessionTimeoutTest'

mvn -o -Dtest="$TESTS" test

echo
echo "E23_SATURATION_RECOVERY_BATTERY SUCCESS"
