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
IMAGE_ID="${IMAGE_ID:-demo-grande}"
METRICS_INTERVAL_MS="${METRICS_INTERVAL_MS:-200}"
SAMPLE_SOURCE="${SAMPLE_SOURCE:-unspecified}"
SAMPLE_PERMISSION="${SAMPLE_PERMISSION:-unspecified}"
JPEG_QUALITY_NOTE="${JPEG_QUALITY_NOTE:-unknown-not-persisted-in-manifest}"

[[ "$MODE" == normal || "$MODE" == no-cancel ]] || { echo "MODE must be normal or no-cancel" >&2; exit 2; }
[[ "$CACHE" == cold || "$CACHE" == warm ]] || { echo "CACHE must be cold or warm" >&2; exit 2; }
[[ "$CLIENTS" == 1 || "$CLIENTS" == 5 || "$CLIENTS" == 20 ]] || { echo "CLIENTS must be 1, 5 or 20" >&2; exit 2; }
[[ "$REPETITIONS" =~ ^[1-9][0-9]*$ ]] || { echo "REPETITIONS must be positive" >&2; exit 2; }
(( METRICS_INTERVAL_MS >= 50 )) || { echo "METRICS_INTERVAL_MS must be >= 50" >&2; exit 2; }
BASE_CONFIG="scripts/e24/scenarios/${CLIENTS}-${SCENARIO}.properties"
[[ -f "$BASE_CONFIG" ]] || { echo "Missing $BASE_CONFIG" >&2; exit 2; }
[[ -f data/catalog.json ]] || { echo "Missing immutable published data/catalog.json" >&2; exit 2; }

if [[ "${SKIP_BUILD:-0}" != 1 ]]; then mvn -o -q -DskipTests package; fi
CAMPAIGN_ID="e24-$(date -u +%Y%m%dT%H%M%SZ)-${MODE}-${CACHE}-c${CLIENTS}-${SCENARIO}"
CAMPAIGN_DIR="$OUTPUT_ROOT/$CAMPAIGN_ID"
mkdir -p "$CAMPAIGN_DIR/runs"
bash scripts/e24/capture-environment.sh "$CAMPAIGN_DIR/environment.txt" >/dev/null
python3 scripts/e24/describe-dataset.py --data-root=data --image-id="$IMAGE_ID" \
  --source-note="$SAMPLE_SOURCE" --permission-note="$SAMPLE_PERMISSION" \
  --jpeg-quality-note="$JPEG_QUALITY_NOTE" --out="$CAMPAIGN_DIR/dataset.json" >"$CAMPAIGN_DIR/dataset.out"

SERVER_PID=""; SERVER_SAMPLER_PID=""
stop_server() {
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then kill "$SERVER_PID" 2>/dev/null || true; wait "$SERVER_PID" 2>/dev/null || true; fi
  if [[ -n "$SERVER_SAMPLER_PID" ]]; then wait "$SERVER_SAMPLER_PID" 2>/dev/null || true; fi
  SERVER_PID=""; SERVER_SAMPLER_PID=""
}
trap stop_server EXIT

start_server() {
  stop_server
  local log="$1" metrics="$2" proc="$3"
  local -a opts=("-Dlupa.e24.metricsFile=$metrics" "-Dlupa.e24.metricsIntervalMs=$METRICS_INTERVAL_MS")
  if [[ "$MODE" == no-cancel ]]; then opts+=("-Dlupa.e23.experimentalNoCancellation=true" "-Dlupa.e23.experimentalPlanQueue=16"); fi
  java "${opts[@]}" -jar target/lupa.jar --port="$PORT" --data-root="$ROOT/data" >"$log" 2>&1 &
  SERVER_PID=$!
  python3 scripts/e24/sample-process.py --pid="$SERVER_PID" --out="$proc" --interval-ms="$METRICS_INTERVAL_MS" & SERVER_SAMPLER_PID=$!
  for _ in $(seq 1 120); do
    kill -0 "$SERVER_PID" 2>/dev/null || { echo "Server exited; see $log" >&2; return 1; }
    if bash -c "exec 3<>/dev/tcp/127.0.0.1/$PORT" 2>/dev/null; then exec 3>&-; exec 3<&-; return 0; fi
    sleep 0.05
  done
  echo "Server not ready on $PORT" >&2; return 1
}

run_client() {
  local r="$1" phase="$2" run_seed="$3"
  local run_id="${CAMPAIGN_ID}-r${r}-${phase}" commit="$(git rev-parse HEAD)"
  local meta="$CAMPAIGN_DIR/run-r${r}-${phase}.properties" out="$CAMPAIGN_DIR/run-r${r}-${phase}.out"
  local proc="$CAMPAIGN_DIR/client-r${r}-${phase}-process.csv" started client_pid sampler code
  started="$(date --iso-8601=seconds)"
  printf 'runId=%s\nstartedAt=%s\ncommit=%s\nmode=%s\ncache=%s\nclients=%s\nscenario=%s\nseed=%s\nimageId=%s\nserverPid=%s\ntransport=loopback\nserverAndGeneratorSameHost=true\n' \
    "$run_id" "$started" "$commit" "$MODE" "$CACHE" "$CLIENTS" "$SCENARIO" "$run_seed" "$IMAGE_ID" "$SERVER_PID" >"$meta"
  java -De23.commit="$commit" -cp target/lupa.jar gt.lupa.tools.E23LoadClient \
    --config="$BASE_CONFIG" --url="ws://127.0.0.1:$PORT/lupa" --clients="$CLIENTS" --imageId="$IMAGE_ID" \
    --seed="$run_seed" --experimentMode="$MODE" --outputDir="$CAMPAIGN_DIR/runs" --runId="$run_id" >"$out" 2>&1 &
  client_pid=$!
  python3 scripts/e24/sample-process.py --pid="$client_pid" --out="$proc" --interval-ms="$METRICS_INTERVAL_MS" & sampler=$!
  set +e; wait "$client_pid"; code=$?; set -e
  wait "$sampler" 2>/dev/null || true
  printf 'endedAt=%s\nclientPid=%s\nexitCode=%s\n' "$(date --iso-8601=seconds)" "$client_pid" "$code" >>"$meta"
  return "$code"
}

cat >"$CAMPAIGN_DIR/campaign.properties" <<EOF
campaignId=$CAMPAIGN_ID
commit=$(git rev-parse HEAD)
mode=$MODE
cache=$CACHE
clients=$CLIENTS
scenario=$SCENARIO
repetitions=$REPETITIONS
baseSeed=$SEED
imageId=$IMAGE_ID
metricsIntervalMs=$METRICS_INTERVAL_MS
cacheDefinition=application-cache-only
osPageCacheControlled=false
transport=loopback
serverAndGeneratorSameHost=true
baselinePlanQueue=16
EOF
printf 'repetition,status,exitCode,seed\n' >"$CAMPAIGN_DIR/status.csv"

if [[ "$CACHE" == warm ]]; then
  start_server "$CAMPAIGN_DIR/server-warm.log" "$CAMPAIGN_DIR/server-warm-metrics.csv" "$CAMPAIGN_DIR/server-warm-process.csv"
  run_client 0 warmup $((SEED - 1)) || { echo "Warmup failed; evidence retained" >&2; exit 1; }
fi
for r in $(seq 1 "$REPETITIONS"); do
  run_seed=$((SEED + r - 1))
  echo "=== E24 r=$r/$REPETITIONS mode=$MODE cache=$CACHE clients=$CLIENTS scenario=$SCENARIO seed=$run_seed ==="
  if [[ "$CACHE" == cold ]]; then start_server "$CAMPAIGN_DIR/server-r${r}.log" "$CAMPAIGN_DIR/server-r${r}-metrics.csv" "$CAMPAIGN_DIR/server-r${r}-process.csv"; fi
  if run_client "$r" measured "$run_seed"; then printf '%s,success,0,%s\n' "$r" "$run_seed" >>"$CAMPAIGN_DIR/status.csv"; else code=$?; printf '%s,failure,%s,%s\n' "$r" "$code" "$run_seed" >>"$CAMPAIGN_DIR/status.csv"; fi
  if [[ "$CACHE" == cold ]]; then stop_server; fi
done
if [[ "$CACHE" == warm ]]; then stop_server; fi
python3 scripts/e24/summarize-campaign.py "$CAMPAIGN_DIR"
echo "E24 campaign: $CAMPAIGN_DIR"
echo "OS page cache was not flushed; CACHE refers only to LUPA application cache."
