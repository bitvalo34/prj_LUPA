#!/usr/bin/env python3
import json
import math
import statistics
import sys
from pathlib import Path

def load(path):
    with Path(path).open("r", encoding="utf-8") as f:
        return json.load(f)

def samples(summary, field):
    values = []
    for client in summary.get("clients", []):
        for view in client.get("views", []):
            start = view.get("viewSentNanos", 0)
            end = view.get(field, 0)
            if start and end and end >= start:
                values.append((end - start) / 1_000_000.0)
    return values

def percentile_nearest_rank(values, p):
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil((p / 100.0) * len(ordered)))
    return ordered[rank - 1]

def fmt(value):
    return "n/a" if value is None else f"{value:.3f}"

def report(label, summary):
    first = samples(summary, "firstUsefulTileNanos")
    done = samples(summary, "doneNanos")
    return {
        "label": label,
        "runId": summary.get("runId"),
        "mode": summary.get("mode"),
        "clients": summary.get("configuredClients"),
        "success": summary.get("successfulClients"),
        "failed": summary.get("failedClients"),
        "viewsSent": summary.get("viewsSent"),
        "viewsCompleted": summary.get("viewsCompleted"),
        "incompleteViews": summary.get("incompleteViews"),
        "tiles": summary.get("tiles"),
        "jpegBytes": summary.get("jpegBytes"),
        "obsoleteTiles": summary.get("obsoleteTiles"),
        "obsoleteJpegBytes": summary.get("obsoleteJpegBytes"),
        "errors": summary.get("errors"),
        "firstN": len(first),
        "firstMedianMs": statistics.median(first) if first else None,
        "firstP95Ms": percentile_nearest_rank(first, 95),
        "doneN": len(done),
        "doneMedianMs": statistics.median(done) if done else None,
        "doneP95Ms": percentile_nearest_rank(done, 95),
    }

def main():
    if len(sys.argv) != 3:
        print("Usage: compare-load-runs.py <normal-summary.json> <no-cancel-summary.json>", file=sys.stderr)
        return 2

    normal = report("normal", load(sys.argv[1]))
    baseline = report("no-cancel", load(sys.argv[2]))

    print("E23 PRELIMINARY COMPARISON")
    print("Percentile method: nearest-rank over successful per-VIEW observations.")
    print("These are application/client timings, not browser render timings.\n")
    header = (
        "mode", "clients", "ok", "failed", "views", "done", "incomplete",
        "tiles", "jpegBytes", "obsoleteTiles", "obsoleteBytes",
        "firstN", "firstMedianMs", "firstP95Ms", "doneN", "doneMedianMs", "doneP95Ms", "errors"
    )
    print(",".join(header))
    for row in (normal, baseline):
        print(",".join(map(str, (
            row["mode"], row["clients"], row["success"], row["failed"],
            row["viewsSent"], row["viewsCompleted"], row["incompleteViews"],
            row["tiles"], row["jpegBytes"], row["obsoleteTiles"], row["obsoleteJpegBytes"],
            row["firstN"], fmt(row["firstMedianMs"]), fmt(row["firstP95Ms"]),
            row["doneN"], fmt(row["doneMedianMs"]), fmt(row["doneP95Ms"]), row["errors"]
        ))))

    if normal["clients"] != baseline["clients"]:
        print("\nWARNING: client counts differ; do not interpret as an equivalent comparison.", file=sys.stderr)
        return 1
    if normal["failed"] or baseline["failed"] or normal["errors"] or baseline["errors"]:
        print("\nWARNING: one run contains failures/errors; retain it as evidence but do not present it as clean comparison.", file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
