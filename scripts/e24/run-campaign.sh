#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

MODE="${MODE:-normal}"
CACHE="${CACHE:-cold}"
CLIENTS="${CLIENTS:-5}"
REPETITIONS="${REPETITIONS:-5}"
PORT="${PORT:-8081}"
SCENARIO="${SCENARIO:-aggressive}"
OUTPUT_ROOT="${OUTPUT_ROOT:-results/e24}"
SEED="${SEED:-230023}"

if [[ "$MODE" != "normal" && "$MODE" != "no-cancel" ]]; then
  echo "MODE must be normal or no-cancel" >&2
  exit 2
fi
if [[ "$CACHE" != "cold" && "$CACHE" != "warm" ]]; then
  echo "CACHE must be cold or warm" >&2
  exit 2
fi
if [[ "$CLIENTS" != "1" && "$CLIENTS" != "5" && "$CLIENTS" != "20" ]]; then
  echo "CLIENTS must be 1, 5 or 20" >&2
  exit 2
fi
if ! [[ "$REPETITIONS" =~ ^[1-9][0-9]*$ ]]; then
  echo "REPETITIONS must be a positive integer" >&2
  exit 2
fi

BASE_CONFIG="scripts/e24/scenarios/${CLIENTS}-${SCENARIO}.properties"
if [[ ! -f "$BASE_CONFIG" ]]; then
  echo "Missing scenario config: $BASE_CONFIG" >&2
  exit 2
fi

mvn -o -q -DskipTests package

CAMPAIGN_ID="e24-$(date -u +%Y%m%dT%H%M%SZ)-${MODE}-${CACHE}-c${CLIENTS}-${SCENARIO}"
CAMPAIGN_DIR="$OUTPUT_ROOT/$CAMPAIGN_ID"
mkdir -p "$CAMPAIGN_DIR"

SERVER_PID=""
stop_server() {
  if [[ -n "${SERVER_PID:-}" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  SERVER_PID=""
}
trap stop_server EXIT

start_server() {
  stop_server
  local log_path="$1"
  if [[ "$MODE" == "normal" ]]; then
    java -jar target/lupa.jar       --port="$PORT"       --data-root="$ROOT/data"       >"$log_path" 2>&1 &
  else
    java       -Dlupa.e23.experimentalNoCancellation=true       -Dlupa.e23.experimentalPlanQueue=16       -jar target/lupa.jar       --port="$PORT"       --data-root="$ROOT/data"       >"$log_path" 2>&1 &
  fi
  SERVER_PID=$!

  for _ in $(seq 1 100); do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      echo "Server exited before becoming ready. See $log_path" >&2
      return 1
    fi
    if bash -c "exec 3<>/dev/tcp/127.0.0.1/$PORT" 2>/dev/null; then
      exec 3>&-
      exec 3<&-
      return 0
    fi
    sleep 0.05
  done
  echo "Server did not become ready on port $PORT. See $log_path" >&2
  return 1
}

run_client() {
  local repetition="$1"
  local phase="$2"
  local run_id="${CAMPAIGN_ID}-r${repetition}-${phase}"
  local commit
  commit="$(git rev-parse HEAD)"

  java     -De23.commit="$commit"     -cp target/lupa.jar     gt.lupa.tools.E23LoadClient     --config="$BASE_CONFIG"     --url="ws://127.0.0.1:$PORT/lupa"     --clients="$CLIENTS"     --seed="$SEED"     --experimentMode="$MODE"     --outputDir="$CAMPAIGN_DIR/runs"     --runId="$run_id"
}

cat > "$CAMPAIGN_DIR/campaign.properties" <<EOF
campaignId=$CAMPAIGN_ID
commit=$(git rev-parse HEAD)
mode=$MODE
cache=$CACHE
clients=$CLIENTS
scenario=$SCENARIO
repetitions=$REPETITIONS
seed=$SEED
port=$PORT
cacheDefinition=application-cache-only
osPageCacheControlled=false
EOF

if [[ "$CACHE" == "warm" ]]; then
  start_server "$CAMPAIGN_DIR/server-warm.log"
  echo "Warming application cache with one unmeasured run..."
  run_client 0 warmup >"$CAMPAIGN_DIR/warmup.out" 2>&1
fi

for r in $(seq 1 "$REPETITIONS"); do
  echo "=== E24 repetition $r/$REPETITIONS mode=$MODE cache=$CACHE clients=$CLIENTS ==="

  if [[ "$CACHE" == "cold" ]]; then
    start_server "$CAMPAIGN_DIR/server-r${r}.log"
  elif [[ -z "$SERVER_PID" ]]; then
    start_server "$CAMPAIGN_DIR/server-warm.log"
  fi

  if run_client "$r" measured >"$CAMPAIGN_DIR/run-r${r}.out" 2>&1; then
    echo "r=$r,status=success" >>"$CAMPAIGN_DIR/status.csv"
  else
    code=$?
    echo "r=$r,status=failure,exitCode=$code" >>"$CAMPAIGN_DIR/status.csv"
  fi

  if [[ "$CACHE" == "cold" ]]; then
    stop_server
  fi
done

python3 scripts/e24/summarize-campaign.py "$CAMPAIGN_DIR"

echo
echo "E24 campaign prepared/executed: $CAMPAIGN_DIR"
if [[ "$CACHE" == "cold" ]]; then
  echo "CACHE=cold: each measured repetition uses a fresh LUPA JVM/application cache."
else
  echo "CACHE=warm: one persistent LUPA JVM is warmed by one unmeasured run before measured repetitions."
fi
echo "The OS page cache is not flushed in either condition."
