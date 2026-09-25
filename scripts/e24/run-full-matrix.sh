#!/usr/bin/env bash
set -uo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

REPETITIONS="${REPETITIONS:-5}"
SCENARIO="${SCENARIO:-aggressive}"
PORT="${PORT:-8081}"
SEED="${SEED:-230023}"
MATRIX_ROOT="${MATRIX_ROOT:-results/e24/formal-$(date -u +%Y%m%dT%H%M%SZ)}"

if ! [[ "$REPETITIONS" =~ ^[1-9][0-9]*$ ]]; then
  echo "REPETITIONS must be a positive integer" >&2
  exit 2
fi

mkdir -p "$MATRIX_ROOT"
STATUS="$MATRIX_ROOT/matrix-status.csv"
printf 'mode,cache,clients,status,exitCode\n' >"$STATUS"

cat >"$MATRIX_ROOT/matrix.properties" <<EOF
commit=$(git rev-parse HEAD)
repetitions=$REPETITIONS
scenario=$SCENARIO
seed=$SEED
port=$PORT
conditions=12
measuredRuns=$((12 * REPETITIONS))
cacheScope=application-cache-only
osPageCacheControlled=false
EOF

echo "E24 formal matrix"
echo "output=$MATRIX_ROOT"
echo "conditions=12"
echo "measured runs=$((12 * REPETITIONS))"
echo

failures=0

for mode in normal no-cancel; do
  for cache in cold warm; do
    for clients in 1 5 20; do
      echo
      echo "================================================================"
      echo "mode=$mode cache=$cache clients=$clients repetitions=$REPETITIONS"
      echo "================================================================"

      if MODE="$mode"          CACHE="$cache"          CLIENTS="$clients"          REPETITIONS="$REPETITIONS"          SCENARIO="$SCENARIO"          PORT="$PORT"          SEED="$SEED"          OUTPUT_ROOT="$MATRIX_ROOT"          bash scripts/e24/run-campaign.sh; then
        printf '%s,%s,%s,success,0\n' "$mode" "$cache" "$clients" >>"$STATUS"
      else
        code=$?
        failures=$((failures + 1))
        printf '%s,%s,%s,failure,%s\n' "$mode" "$cache" "$clients" "$code" >>"$STATUS"
        echo "Condition failed; evidence was retained. Continuing with the matrix." >&2
      fi
    done
  done
done

python3 scripts/e24/validate-matrix.py "$MATRIX_ROOT" "$REPETITIONS"
validator=$?

echo
echo "E24 formal matrix finished: $MATRIX_ROOT"
echo "conditionFailures=$failures validatorExit=$validator"

if (( failures > 0 || validator != 0 )); then
  exit 1
fi
