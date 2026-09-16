#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /absolute/path/to/authorized-photo.jpg" >&2
  exit 2
fi

SOURCE_JPEG="$(realpath "$1")"
ROOT="$(pwd)"
RUN_ROOT="$(mktemp -d /tmp/lupa-a19-final-XXXXXX)"
SAMPLES="$RUN_ROOT/samples"
DATA_ROOT="$RUN_ROOT/data"
LOG="$RUN_ROOT/results.txt"
mkdir -p "$SAMPLES"

cleanup() {
  if [[ "${KEEP_A19_FINAL_RUN:-0}" != "1" ]]; then
    rm -rf "$RUN_ROOT"
  else
    echo "KEEP_A19_FINAL_RUN=1 -> kept $RUN_ROOT"
  fi
}
trap cleanup EXIT

exec > >(tee "$LOG") 2>&1

echo "A19 final validation"
echo "repo=$ROOT"
echo "runRoot=$RUN_ROOT"
echo "source=$SOURCE_JPEG"
echo

[[ -f "$SOURCE_JPEG" ]] || { echo "Source JPEG does not exist"; exit 2; }
command -v java >/dev/null
command -v mvn >/dev/null
command -v vips >/dev/null
command -v vipsheader >/dev/null
command -v sha256sum >/dev/null

LOADER="$(vipsheader -f vips-loader "$SOURCE_JPEG")"
[[ "$LOADER" == *jpegload* ]] || { echo "Source must be a real JPEG; loader=$LOADER"; exit 2; }

if [[ "${OFFLINE_ASSERT:-0}" == "1" ]]; then
  if curl -fsSI --max-time 3 https://repo.maven.apache.org/maven2/ >/dev/null 2>&1; then
    echo "OFFLINE_ASSERT=1 but network access is still available" >&2
    exit 6
  fi
fi

SOURCE_HASH_BEFORE="$(sha256sum "$SOURCE_JPEG" | awk '{print $1}')"
SOURCE_W="$(vipsheader -f width "$SOURCE_JPEG")"
SOURCE_H="$(vipsheader -f height "$SOURCE_JPEG")"
SOURCE_BYTES="$(stat -c %s "$SOURCE_JPEG")"

echo "java=$(java -version 2>&1 | head -n1)"
echo "maven=$(mvn -version | head -n1)"
echo "vips=$(vips --version)"
echo "sourceDimensions=${SOURCE_W}x${SOURCE_H}"
echo "sourceBytes=$SOURCE_BYTES"
echo "sourceSha256=$SOURCE_HASH_BEFORE"
echo

echo "[1/5] Maven offline build"
mvn -o clean verify package

echo

echo "[2/5] Prepare local photographic samples"
SMALL_JPEG="$SAMPLES/small-photo.jpg"
TIFF_FILE="$SAMPLES/real-photo.tiff"
LARGE_JPEG="$SAMPLES/large-photo.jpg"
cp "$SOURCE_JPEG" "$SMALL_JPEG"
vips tiffsave "$SOURCE_JPEG" "$TIFF_FILE" --compression=lzw
vips resize "$SOURCE_JPEG" "$LARGE_JPEG[Q=90]" 4.0 --kernel=lanczos3

for sample in "$SMALL_JPEG" "$TIFF_FILE" "$LARGE_JPEG"; do
  echo "sample=$(basename "$sample") loader=$(vipsheader -f vips-loader "$sample") dimensions=$(vipsheader -f width "$sample")x$(vipsheader -f height "$sample") bytes=$(stat -c %s "$sample") sha256=$(sha256sum "$sample" | awk '{print $1}')"
done

echo

echo "[3/5] Import JPEG small, TIFF real, JPEG large"
import_one() {
  local file="$1" image_id="$2" display="$3" license="$4"
  local start end
  start="$(date +%s%N)"
  java -cp target/lupa.jar gt.lupa.ingest.IngestApplication import \
    --original="$file" \
    --image-id="$image_id" \
    --display-name="$display" \
    --license-ref="$license" \
    --data-root="$DATA_ROOT" \
    --vips=vips \
    --vipsheader=vipsheader \
    --timeout-seconds=600 \
    --jpeg-quality=85
  end="$(date +%s%N)"
  echo "durationMs[$image_id]=$(( (end - start) / 1000000 ))"
}

LICENSE="Fotografia propia/autorizada de Erwin; derivadas locales solo para validacion A19"
import_one "$SMALL_JPEG" "a19-small" "A19 Small Photo" "$LICENSE"
import_one "$TIFF_FILE" "a19-tiff" "A19 TIFF Photo" "$LICENSE"
import_one "$LARGE_JPEG" "a19-large" "A19 Large Manageable Photo" "$LICENSE"

echo

echo "[4/5] Verify immutable private originals and published catalog"
[[ "$(sha256sum "$SMALL_JPEG" | awk '{print $1}')" == "$(sha256sum "$DATA_ROOT/originals/a19-small/v1/source.jpg" | awk '{print $1}')" ]]
[[ "$(sha256sum "$TIFF_FILE" | awk '{print $1}')" == "$(sha256sum "$DATA_ROOT/originals/a19-tiff/v1/source.tiff" | awk '{print $1}')" ]]
[[ "$(sha256sum "$LARGE_JPEG" | awk '{print $1}')" == "$(sha256sum "$DATA_ROOT/originals/a19-large/v1/source.jpg" | awk '{print $1}')" ]]
[[ "$SOURCE_HASH_BEFORE" == "$(sha256sum "$SOURCE_JPEG" | awk '{print $1}')" ]]

cat "$DATA_ROOT/catalog.json"
echo
find "$DATA_ROOT/pyramids" -type f -name '*.jpg' -printf '%s\n' | awk 'BEGIN{max=0;n=0;sum=0}{if($1>max)max=$1;n++;sum+=$1}END{printf "publishedTiles=%d tileJpegBytes=%d maxTileBytes=%d\n",n,sum,max}'
find "$DATA_ROOT" -type f -printf '%s\n' | awk '{sum+=$1}END{printf "finalDataBytes=%d\n",sum}'

echo

echo "[5/5] E19 compatibility smoke test with real catalog"
PORT="${A19_TEST_PORT:-18081}"
java -jar target/lupa.jar --host=127.0.0.1 --port="$PORT" --catalog=file --catalog-path="$DATA_ROOT/catalog.json" >"$RUN_ROOT/server.log" 2>&1 &
SERVER_PID=$!
stop_server() { kill "$SERVER_PID" >/dev/null 2>&1 || true; wait "$SERVER_PID" >/dev/null 2>&1 || true; }
trap 'stop_server; cleanup' EXIT
for _ in {1..30}; do
  if curl -fsS "http://127.0.0.1:$PORT/api/catalog" >"$RUN_ROOT/catalog-from-http.json"; then
    break
  fi
  sleep 0.2
done
curl -fsS "http://127.0.0.1:$PORT/api/catalog"
echo
stop_server
trap cleanup EXIT

echo
SOURCE_HASH_AFTER="$(sha256sum "$SOURCE_JPEG" | awk '{print $1}')"
[[ "$SOURCE_HASH_BEFORE" == "$SOURCE_HASH_AFTER" ]]
echo "sourceSha256After=$SOURCE_HASH_AFTER"
echo "A19 FINAL VALIDATION OK"
echo "results=$LOG"
