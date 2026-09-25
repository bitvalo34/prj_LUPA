#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

echo "========== E23 FINAL VERIFICATION =========="
echo "commit=$(git rev-parse HEAD)"
echo "branch=$(git branch --show-current)"
echo "java=$(java -version 2>&1 | head -n 1)"
echo "maven=$(mvn -version | head -n 1)"
echo

bash scripts/e23/verify-backend-errors.sh
bash scripts/e23/verify-saturation-recovery.sh
bash scripts/e23/verify-no-cancellation-baseline.sh

echo
echo "========== E23 FULL OFFLINE REGRESSION =========="
mvn -o clean verify package

echo
echo "E23_FINAL_VERIFICATION SUCCESS"
