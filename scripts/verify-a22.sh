#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: bash scripts/verify-a22.sh /absolute/path/to/authorized-photo.jpg" >&2
  exit 2
fi

cd "$(dirname "$0")/.."
SOURCE_JPEG="$(realpath "$1")"
RUN_ROOT="$(mktemp -d /tmp/lupa-a22-XXXXXX)"
DATA_ROOT="$RUN_ROOT/data"
LOG="$RUN_ROOT/results.txt"
PORT="${A22_TEST_PORT:-18082}"
SERVER_PID=""

cleanup() {
  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" >/dev/null 2>&1 || true
    wait "$SERVER_PID" >/dev/null 2>&1 || true
  fi
  if [[ "${KEEP_A22_RUN:-0}" != "1" ]]; then
    rm -rf "$RUN_ROOT"
  else
    echo "KEEP_A22_RUN=1 -> kept $RUN_ROOT"
  fi
}
trap cleanup EXIT

exec > >(tee "$LOG") 2>&1

[[ -f "$SOURCE_JPEG" ]] || { echo "Source JPEG does not exist"; exit 2; }
[[ -f target/lupa.jar ]] || { echo "Build target/lupa.jar first with Maven 3.9.x"; exit 2; }
command -v java >/dev/null
command -v vips >/dev/null
command -v vipsheader >/dev/null
command -v curl >/dev/null
command -v sha256sum >/dev/null
command -v python3 >/dev/null

python3 - "$PWD/src/main/resources/web" "$PWD/target/lupa.jar" <<'PY'
from pathlib import Path
import sys
import zipfile

source_root = Path(sys.argv[1])
jar_path = Path(sys.argv[2])
source_files = {
    path.relative_to(source_root).as_posix(): path.read_bytes()
    for path in source_root.rglob("*")
    if path.is_file()
}
with zipfile.ZipFile(jar_path) as archive:
    jar_names = {
        name.removeprefix("web/")
        for name in archive.namelist()
        if name.startswith("web/") and not name.endswith("/")
    }
    missing = sorted(source_files.keys() - jar_names)
    extra = sorted(jar_names - source_files.keys())
    changed = sorted(
        name for name, expected in source_files.items()
        if name in jar_names and archive.read("web/" + name) != expected
    )

if missing or extra or changed:
    print("target/lupa.jar does not contain the current frontend build", file=sys.stderr)
    print("missing=" + repr(missing), file=sys.stderr)
    print("extra=" + repr(extra), file=sys.stderr)
    print("changed=" + repr(changed), file=sys.stderr)
    print("Run the frontend build and then: mvn -o clean verify", file=sys.stderr)
    raise SystemExit(2)
PY

LOADER="$(vipsheader -f vips-loader "$SOURCE_JPEG")"
[[ "$LOADER" == *jpegload* ]] || { echo "Source must be a real JPEG; loader=$LOADER"; exit 2; }

import_image() {
  local image_id="$1" display_name="$2" source="$3"
  java -cp target/lupa.jar gt.lupa.ingest.IngestApplication import \
    --original="$source" \
    --image-id="$image_id" \
    --display-name="$display_name" \
    --license-ref="Authorized local A22 validation image" \
    --data-root="$DATA_ROOT" \
    --vips=vips \
    --vipsheader=vipsheader \
    --timeout-seconds=600 \
    --jpeg-quality=85
}

fetch_catalog() {
  curl -fsS "http://127.0.0.1:$PORT/api/catalog"
}

assert_catalog() {
  local expected_primary="$1"
  fetch_catalog | python3 -c '
import json, sys
expected = sys.argv[1]
images = {item["imageId"]: item["imageVersion"] for item in json.load(sys.stdin)["images"]}
required = {"a22-primary": expected, "a22-secondary": "v1"}
if images != required:
    print(f"unexpected catalog: {images!r}; expected {required!r}", file=sys.stderr)
    raise SystemExit(1)
' "$expected_primary"
}

echo "A22 isolated import/version validation"
echo "runRoot=$RUN_ROOT"
echo "source=$SOURCE_JPEG"
echo "sourceSha256=$(sha256sum "$SOURCE_JPEG" | awk '{print $1}')"
echo

echo "[1/5] Publish the first image"
import_image "a22-primary" "A22 primary" "$SOURCE_JPEG"

echo "[2/5] Start one server process"
java -jar target/lupa.jar \
  --host=127.0.0.1 \
  --port="$PORT" \
  --data-root="$DATA_ROOT" >"$RUN_ROOT/server.log" 2>&1 &
SERVER_PID=$!
for _ in {1..50}; do
  if fetch_catalog >"$RUN_ROOT/catalog-initial.json" 2>/dev/null; then
    break
  fi
  sleep 0.2
done
fetch_catalog >/dev/null

echo "[3/5] Publish a second image without restarting the server"
import_image "a22-secondary" "A22 secondary" "$SOURCE_JPEG"
assert_catalog "v1"

echo "[4/5] Reimport the first image and expose v2"
import_image "a22-primary" "A22 primary v2" "$SOURCE_JPEG"
assert_catalog "v2"
[[ -f "$DATA_ROOT/pyramids/a22-primary/v1/manifest.json" ]]
[[ -f "$DATA_ROOT/pyramids/a22-primary/v2/manifest.json" ]]
[[ -f "$DATA_ROOT/pyramids/a22-secondary/v1/manifest.json" ]]

echo "[5/5] Force a failed import and prove the catalog is unchanged"
CATALOG_HASH_BEFORE="$(sha256sum "$DATA_ROOT/catalog.json" | awk '{print $1}')"
printf 'not a jpeg\n' >"$RUN_ROOT/invalid.jpg"
set +e
import_image "a22-broken" "A22 invalid" "$RUN_ROOT/invalid.jpg"
FAILURE_STATUS=$?
set -e
[[ "$FAILURE_STATUS" -ne 0 ]] || { echo "Invalid import unexpectedly succeeded"; exit 1; }
CATALOG_HASH_AFTER="$(sha256sum "$DATA_ROOT/catalog.json" | awk '{print $1}')"
[[ "$CATALOG_HASH_BEFORE" == "$CATALOG_HASH_AFTER" ]]
assert_catalog "v2"

echo
echo "catalogSha256BeforeFailure=$CATALOG_HASH_BEFORE"
echo "catalogSha256AfterFailure=$CATALOG_HASH_AFTER"
echo "failureExit=$FAILURE_STATUS"
echo "A22 IMPORT VALIDATION OK"
echo "results=$LOG"
