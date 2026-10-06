#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
run="$(pwd)/results/a24/import"
mkdir -p "$run"
data="$(mktemp -d "$run/data-XXXXXX")"
printf '%s\n' "$data" > "$run/data-root.txt"
sha256sum prueba-real.jpg > "$run/original-before.sha256"
java -cp target/lupa.jar gt.lupa.ingest.IngestApplication import \
  --original="$(pwd)/prueba-real.jpg" --image-id=a24-proof \
  --display-name="A24 import verification" --license-ref="Existing authorized project sample" \
  --data-root="$data" --vips=vips --vipsheader=vipsheader --timeout-seconds=120 \
  --jpeg-quality=85 > "$run/valid.log" 2>&1
sha256sum "$data/catalog.json" > "$run/catalog-before.sha256"
printf 'not an image\n' > "$run/invalid.jpg"
set +e
java -cp target/lupa.jar gt.lupa.ingest.IngestApplication import \
  --original="$run/invalid.jpg" --image-id=a24-invalid \
  --display-name="A24 invalid input" --license-ref="Generated negative test" \
  --data-root="$data" --vips=vips --vipsheader=vipsheader --timeout-seconds=30 \
  --jpeg-quality=85 > "$run/invalid.log" 2>&1
status=$?
set -e
test "$status" -ne 0
sha256sum "$data/catalog.json" > "$run/catalog-after.sha256"
sha256sum prueba-real.jpg > "$run/original-after.sha256"
diff "$run/catalog-before.sha256" "$run/catalog-after.sha256"
diff "$run/original-before.sha256" "$run/original-after.sha256"
cp "$data/catalog.json" "$run/catalog.json"
printf 'PASS valid import; invalid exit=%s; catalog unchanged; original unchanged\n' "$status" | tee "$run/result.txt"
