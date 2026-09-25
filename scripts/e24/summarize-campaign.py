#!/usr/bin/env python3
import csv
import json
import math
import statistics
import sys
from pathlib import Path

def percentile_nearest_rank(values, p):
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil((p / 100.0) * len(ordered)))
    return ordered[rank - 1]

def ms(delta):
    return delta / 1_000_000.0

def fmt(v):
    return "" if v is None else f"{v:.3f}"

def main():
    if len(sys.argv) != 2:
        print("Usage: summarize-campaign.py <campaign-dir>", file=sys.stderr)
        return 2

    campaign = Path(sys.argv[1])
    summaries = sorted(campaign.glob("runs/*/summary.json"))
    measured = []
    for path in summaries:
        data = json.loads(path.read_text(encoding="utf-8"))
        if "-r0-warmup" in data.get("runId", ""):
            continue
        measured.append((path, data))

    rows = []
    first_samples = []
    done_samples = []
    total_views = total_completed = total_incomplete = 0
    total_tiles = total_jpeg = total_obsolete_tiles = total_obsolete_bytes = 0
    total_errors = total_failed_clients = 0

    for path, data in measured:
        run_first = []
        run_done = []
        for client in data.get("clients", []):
            for view in client.get("views", []):
                start = view.get("viewSentNanos", 0)
                first = view.get("firstUsefulTileNanos", 0)
                done = view.get("doneNanos", 0)
                if start and first and first >= start:
                    value = ms(first - start)
                    run_first.append(value)
                    first_samples.append(value)
                if start and done and done >= start:
                    value = ms(done - start)
                    run_done.append(value)
                    done_samples.append(value)

        total_views += data.get("viewsSent", 0)
        total_completed += data.get("viewsCompleted", 0)
        total_incomplete += data.get("incompleteViews", 0)
        total_tiles += data.get("tiles", 0)
        total_jpeg += data.get("jpegBytes", 0)
        total_obsolete_tiles += data.get("obsoleteTiles", 0)
        total_obsolete_bytes += data.get("obsoleteJpegBytes", 0)
        total_errors += data.get("errors", 0)
        total_failed_clients += data.get("failedClients", 0)

        rows.append({
            "runId": data.get("runId"),
            "successfulClients": data.get("successfulClients"),
            "failedClients": data.get("failedClients"),
            "viewsSent": data.get("viewsSent"),
            "viewsCompleted": data.get("viewsCompleted"),
            "incompleteViews": data.get("incompleteViews"),
            "tiles": data.get("tiles"),
            "jpegBytes": data.get("jpegBytes"),
            "obsoleteTiles": data.get("obsoleteTiles"),
            "obsoleteJpegBytes": data.get("obsoleteJpegBytes"),
            "firstUsefulN": len(run_first),
            "firstUsefulMedianMs": statistics.median(run_first) if run_first else None,
            "firstUsefulP95Ms": percentile_nearest_rank(run_first, 95),
            "doneN": len(run_done),
            "doneMedianMs": statistics.median(run_done) if run_done else None,
            "doneP95Ms": percentile_nearest_rank(run_done, 95),
            "errors": data.get("errors", 0),
        })

    with (campaign / "runs.csv").open("w", newline="", encoding="utf-8") as f:
        fieldnames = list(rows[0].keys()) if rows else [
            "runId","successfulClients","failedClients","viewsSent","viewsCompleted",
            "incompleteViews","tiles","jpegBytes","obsoleteTiles","obsoleteJpegBytes",
            "firstUsefulN","firstUsefulMedianMs","firstUsefulP95Ms",
            "doneN","doneMedianMs","doneP95Ms","errors"
        ]
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            out = dict(row)
            for k in ("firstUsefulMedianMs","firstUsefulP95Ms","doneMedianMs","doneP95Ms"):
                out[k] = fmt(out[k])
            writer.writerow(out)

    aggregate = {
        "schemaVersion": 1,
        "campaignDir": str(campaign),
        "repetitionsFound": len(measured),
        "viewsSent": total_views,
        "viewsCompleted": total_completed,
        "incompleteViews": total_incomplete,
        "tiles": total_tiles,
        "jpegBytes": total_jpeg,
        "obsoleteTiles": total_obsolete_tiles,
        "obsoleteJpegBytes": total_obsolete_bytes,
        "failedClients": total_failed_clients,
        "errors": total_errors,
        "firstUseful": {
            "unit": "view",
            "n": len(first_samples),
            "medianMs": statistics.median(first_samples) if first_samples else None,
            "p95Ms": percentile_nearest_rank(first_samples, 95),
            "percentileMethod": "nearest-rank"
        },
        "done": {
            "unit": "view",
            "n": len(done_samples),
            "medianMs": statistics.median(done_samples) if done_samples else None,
            "p95Ms": percentile_nearest_rank(done_samples, 95),
            "percentileMethod": "nearest-rank"
        },
        "note": "Medians/p95 use successful per-VIEW observations, not five run averages. Failures remain counted separately."
    }
    (campaign / "aggregate.json").write_text(
        json.dumps(aggregate, indent=2) + "\n", encoding="utf-8"
    )

    print(json.dumps(aggregate, indent=2))
    return 1 if total_errors or total_failed_clients else 0

if __name__ == "__main__":
    raise SystemExit(main())
