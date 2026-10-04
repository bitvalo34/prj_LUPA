#!/usr/bin/env bash
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
REPETITIONS="${REPETITIONS:-5}"
FULL_ROOT="${FULL_ROOT:-results/e24/formal-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$FULL_ROOT"
[[ -n "${SAMPLE_SOURCE:-}" && -n "${SAMPLE_PERMISSION:-}" ]] || { echo "Set SAMPLE_SOURCE and SAMPLE_PERMISSION" >&2; exit 2; }
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  echo "Formal E24 requires a clean tracked working tree." >&2; git status --short; exit 2
fi
bash scripts/e24/capture-environment.sh "$FULL_ROOT/environment.txt"
echo "Running E23/I23 backend preflight on exact candidate..."
bash scripts/e23/verify-e23-final.sh | tee "$FULL_ROOT/e23-preflight.txt"
cat >"$FULL_ROOT/campaign-suite.properties" <<EOF
suiteStartedAt=$(date --iso-8601=seconds)
commit=$(git rev-parse HEAD)
workloads=stable,aggressive
repetitions=$REPETITIONS
measuredRunsPerWorkload=$((2*2*3*REPETITIONS))
expectedMeasuredRunsTotal=$((2*2*3*REPETITIONS*2))
EOF
SUITE_FAILURES=0
for scenario in stable aggressive; do
  if ! MATRIX_ROOT="$FULL_ROOT/$scenario" SCENARIO="$scenario" REPETITIONS="$REPETITIONS" SKIP_BUILD=1 bash scripts/e24/run-full-matrix.sh; then
    SUITE_FAILURES=$((SUITE_FAILURES+1)); echo "Workload $scenario retained evidence but failed closure checks." >&2
  fi
done
echo "suiteFailures=$SUITE_FAILURES" >>"$FULL_ROOT/campaign-suite.properties"
echo "E24 suite: $FULL_ROOT"
echo "Expected measured runs: $((2*2*3*REPETITIONS*2))"
echo "Stable: $FULL_ROOT/stable/BACKEND_RESULTS.md"
echo "Dynamic: $FULL_ROOT/aggressive/BACKEND_RESULTS.md"
if (( SUITE_FAILURES>0 )); then exit 1; fi
