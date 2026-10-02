#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <scenario.properties>" >&2
  exit 2
fi

CONFIG="$1"
if [ ! -f "$CONFIG" ]; then
  echo "Scenario not found: $CONFIG" >&2
  exit 2
fi

COMMIT="$(git rev-parse HEAD)"

mvn -o -q -DskipTests package

java   -De23.commit="$COMMIT"   -cp target/lupa.jar   gt.lupa.tools.E23LoadClient   --config="$CONFIG"
