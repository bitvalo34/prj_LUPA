#!/usr/bin/env python3
import csv
import json
import sys
from pathlib import Path

EXPECTED = [
    (mode, cache, clients)
    for mode in ("normal", "no-cancel")
    for cache in ("cold", "warm")
    for clients in (1, 5, 20)
]

def main():
    if len(sys.argv) != 3:
        print("Usage: validate-matrix.py <matrix-root> <expected-repetitions>", file=sys.stderr)
        return 2

    root = Path(sys.argv[1])
    expected_repetitions = int(sys.argv[2])
    if expected_repetitions < 1:
        print("expected repetitions must be positive", file=sys.stderr)
        return 2

    rows = []
    failures = 0

    for mode, cache, clients in EXPECTED:
        pattern = f"e24-*-{mode}-{cache}-c{clients}-aggressive"
        dirs = sorted(p for p in root.glob(pattern) if p.is_dir())
        if len(dirs) != 1:
            rows.append({
                "mode": mode, "cache": cache, "clients": clients,
                "campaignDir": "", "repetitionsFound": 0,
                "failedClients": "", "errors": "",
                "status": f"expected-1-campaign-found-{len(dirs)}"
            })
            failures += 1
            continue

        campaign = dirs[0]
        aggregate_path = campaign / "aggregate.json"
        if not aggregate_path.is_file():
            rows.append({
                "mode": mode, "cache": cache, "clients": clients,
                "campaignDir": str(campaign), "repetitionsFound": 0,
                "failedClients": "", "errors": "",
                "status": "missing-aggregate"
            })
            failures += 1
            continue

        data = json.loads(aggregate_path.read_text(encoding="utf-8"))
        reps = data.get("repetitionsFound", 0)
        failed_clients = data.get("failedClients", 0)
        errors = data.get("errors", 0)

        status = "success"
        if reps != expected_repetitions:
            status = f"wrong-repetition-count-{reps}"
            failures += 1
        elif failed_clients or errors:
            status = "contains-failures-or-errors"
            failures += 1

        rows.append({
            "mode": mode,
            "cache": cache,
            "clients": clients,
            "campaignDir": str(campaign),
            "repetitionsFound": reps,
            "failedClients": failed_clients,
            "errors": errors,
            "status": status,
        })

    output = root / "matrix-validation.csv"
    with output.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(
            f,
            fieldnames=[
                "mode", "cache", "clients", "campaignDir",
                "repetitionsFound", "failedClients", "errors", "status"
            ],
        )
        writer.writeheader()
        writer.writerows(rows)

    print("E24 MATRIX VALIDATION")
    print(f"root={root}")
    for row in rows:
        print(
            f"{row['mode']:9} {row['cache']:4} c={row['clients']:2} "
            f"reps={row['repetitionsFound']} failedClients={row['failedClients']} "
            f"errors={row['errors']} status={row['status']}"
        )
    print(f"validationFile={output}")
    print(f"conditionFailures={failures}")

    return 1 if failures else 0

if __name__ == "__main__":
    raise SystemExit(main())
