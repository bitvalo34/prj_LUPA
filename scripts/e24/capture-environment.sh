#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: capture-environment.sh <output-file>" >&2
  exit 2
fi
OUT="$1"
ROOT="$(git rev-parse --show-toplevel)"
mkdir -p "$(dirname "$OUT")"
{
  echo "===== E24 identity ====="
  echo "capturedAt=$(date --iso-8601=seconds)"
  echo "timezone=$(date +%Z)"
  echo "repo=$ROOT"
  echo "commit=$(git -C "$ROOT" rev-parse HEAD)"
  echo "branch=$(git -C "$ROOT" branch --show-current)"
  echo "remote=$(git -C "$ROOT" remote get-url origin 2>/dev/null || true)"
  if [[ -n "$(git -C "$ROOT" status --porcelain)" ]]; then echo "workingTreeDirty=true"; else echo "workingTreeDirty=false"; fi
  git -C "$ROOT" status --short --branch
  echo "===== Kernel / WSL ====="
  uname -a
  cat /proc/version 2>/dev/null || true
  cat /etc/os-release 2>/dev/null || true
  if grep -qi microsoft /proc/version 2>/dev/null; then echo "detectedWSL=true"; else echo "detectedWSL=false"; fi
  echo "===== CPU ====="
  echo "nproc=$(nproc 2>/dev/null || true)"
  command -v lscpu >/dev/null && lscpu || true
  echo "===== Memory ====="
  command -v free >/dev/null && free -b || true
  grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo 2>/dev/null || true
  echo "===== Storage ====="
  command -v findmnt >/dev/null && findmnt -T "$ROOT" || true
  df -T "$ROOT" || true
  echo "===== Java ====="
  java -version 2>&1 || true
  java -XshowSettings:vm -version 2>&1 || true
  echo "===== Maven ====="
  mvn -version 2>&1 || true
  echo "===== Tools ====="
  python3 --version 2>&1 || true
  command -v node >/dev/null && node --version || true
  command -v npm >/dev/null && npm --version || true
} > "$OUT"
echo "Environment captured: $OUT"
