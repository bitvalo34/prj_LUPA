#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

echo "========== E23 BACKEND ERROR BATTERY =========="
echo "commit=$(git rev-parse HEAD)"
echo "branch=$(git branch --show-current)"
echo "java=$(java -version 2>&1 | head -n 1)"
echo "maven=$(mvn -version | head -n 1)"
echo

TESTS='HeaderAccumulatorTest,HttpParserTest,HttpRouterSecurityTest,NioHttpServerIntegrationTest,NioHttpServerAdmissionTest,AsyncWritePumpTest,WebSocketHandshakeTest,WebSocketFrameParserTest,WebSocketFrameExtendedLengthTest,WebSocketRawIntegrationTest,WebSocketHeartbeatTest,WebSocketWriteQueueTest,WebSocketWriteTimeoutTest,LupaSessionPolicyTest,LupaSessionRobustnessTest,LupaSessionTest,LupaSessionResourceAccountingTest,LupaSessionTimeoutTest,LupaSelectiveAckTest,LupaSessionFairnessTest,StorageExecutorsTest,TileReadAdmissionTest,TransientBufferBudgetTest'

mvn -o -Dtest="$TESTS" test

echo
echo "E23_BACKEND_ERROR_BATTERY SUCCESS"
