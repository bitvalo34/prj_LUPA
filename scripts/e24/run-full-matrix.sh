#!/usr/bin/env bash
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
REPETITIONS="${REPETITIONS:-5}"
SCENARIO="${SCENARIO:-aggressive}"
SEED="${SEED:-230023}"
IMAGE_ID="${IMAGE_ID:-demo-grande}"
PORT="${PORT:-8081}"
SAMPLE_SOURCE="${SAMPLE_SOURCE:-}"
SAMPLE_PERMISSION="${SAMPLE_PERMISSION:-}"
JPEG_QUALITY_NOTE="${JPEG_QUALITY_NOTE:-unknown-not-persisted-in-manifest}"
MATRIX_ROOT="${MATRIX_ROOT:-results/e24/matrix-$(date -u +%Y%m%dT%H%M%SZ)-${SCENARIO}}"

[[ -n "$SAMPLE_SOURCE" && -n "$SAMPLE_PERMISSION" ]] || { echo "Set SAMPLE_SOURCE and SAMPLE_PERMISSION for formal E24" >&2; exit 2; }
mkdir -p "$MATRIX_ROOT"
if [[ "${SKIP_BUILD:-0}" != 1 ]]; then mvn -o -q -DskipTests package; fi
bash scripts/e24/capture-environment.sh "$MATRIX_ROOT/environment.txt" >/dev/null
python3 scripts/e24/describe-dataset.py --data-root=data --image-id="$IMAGE_ID" --source-note="$SAMPLE_SOURCE" \
  --permission-note="$SAMPLE_PERMISSION" --jpeg-quality-note="$JPEG_QUALITY_NOTE" --out="$MATRIX_ROOT/dataset.json" >"$MATRIX_ROOT/dataset.out"
cat >"$MATRIX_ROOT/matrix.properties" <<EOF
matrixStartedAt=$(date --iso-8601=seconds)
commit=$(git rev-parse HEAD)
scenario=$SCENARIO
repetitions=$REPETITIONS
baseSeed=$SEED
imageId=$IMAGE_ID
clients=1,5,20
cache=cold,warm
modes=normal,no-cancel
expectedMeasuredRuns=$((2*2*3*REPETITIONS))
orderPolicy=balanced-by-condition
cacheDefinition=application-cache-only
osPageCacheControlled=false
transport=loopback
serverAndGeneratorSameHost=true
EOF

FAILURES=0; PAIR_INDEX=0
for cache in cold warm; do
  for clients in 1 5 20; do
    if (( PAIR_INDEX % 2 == 0 )); then MODES=(normal no-cancel); else MODES=(no-cancel normal); fi
    PAIR_INDEX=$((PAIR_INDEX+1))
    for mode in "${MODES[@]}"; do
      echo "##### E24 scenario=$SCENARIO mode=$mode cache=$cache clients=$clients #####"
      if ! MODE="$mode" CACHE="$cache" CLIENTS="$clients" REPETITIONS="$REPETITIONS" SCENARIO="$SCENARIO" \
           OUTPUT_ROOT="$MATRIX_ROOT" SEED="$SEED" IMAGE_ID="$IMAGE_ID" PORT="$PORT" \
           SAMPLE_SOURCE="$SAMPLE_SOURCE" SAMPLE_PERMISSION="$SAMPLE_PERMISSION" JPEG_QUALITY_NOTE="$JPEG_QUALITY_NOTE" \
           SKIP_BUILD=1 bash scripts/e24/run-campaign.sh; then
        FAILURES=$((FAILURES+1)); echo "Condition failed; evidence retained. Continuing." >&2
      fi
    done
  done
done

echo "campaignFailures=$FAILURES" >>"$MATRIX_ROOT/matrix.properties"
set +e
python3 scripts/e24/validate-matrix.py "$MATRIX_ROOT" "$REPETITIONS" "$SCENARIO"; VALIDATION=$?
python3 scripts/e24/analyze-matrix.py "$MATRIX_ROOT" "$SCENARIO"; ANALYSIS=$?
set -e
echo "validationExitCode=$VALIDATION" >>"$MATRIX_ROOT/matrix.properties"
echo "analysisExitCode=$ANALYSIS" >>"$MATRIX_ROOT/matrix.properties"
echo "E24 matrix: $MATRIX_ROOT; campaignFailures=$FAILURES validation=$VALIDATION analysis=$ANALYSIS"
if (( FAILURES>0 || VALIDATION!=0 || ANALYSIS!=0 )); then exit 1; fi
