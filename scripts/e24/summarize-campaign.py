#!/usr/bin/env python3
import csv
import json
import math
import statistics
import sys
from datetime import datetime
from pathlib import Path


def pct(values, p):
    if not values: return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(p / 100 * len(ordered)) - 1)]


def props(path):
    out = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1); out[k] = v
    return out


def rows(path):
    if not path.is_file(): return []
    with path.open(newline="", encoding="utf-8") as f: return list(csv.DictReader(f))


def num(row, key):
    try: return float(row.get(key, 0) or 0)
    except (TypeError, ValueError): return 0.0


def time_s(value):
    try: return datetime.fromisoformat(value).timestamp()
    except (TypeError, ValueError): return None


def window(data, start, end):
    if start is None or end is None: return data
    out = []
    for row in data:
        t = time_s(row.get("wallTime"))
        if t is not None and start - .3 <= t <= end + .3: out.append(row)
    return out


def latency(data):
    names = {"scheduleLag": [], "plan": [], "firstUseful": [], "lastTile": [], "done": []}
    for client in data.get("clients", []):
        for view in client.get("views", []):
            sent = view.get("viewSentNanos", 0)
            lag = view.get("scheduleLagNanos", 0)
            if lag >= 0: names["scheduleLag"].append(lag / 1e6)
            for src, dst in (("planNanos","plan"),("firstUsefulTileNanos","firstUseful"),("lastTileNanos","lastTile"),("doneNanos","done")):
                value = view.get(src, 0)
                if sent and value and value >= sent: names[dst].append((value - sent) / 1e6)
    return names


def stat(values):
    return {"n": len(values), "medianMs": statistics.median(values) if values else None,
            "p95Ms": pct(values, 95), "percentileMethod": "nearest-rank"}


def resource(campaign, rep, start, end, warm):
    metrics = window(rows(campaign / ("server-warm-metrics.csv" if warm else f"server-r{rep}-metrics.csv")), start, end)
    process = window(rows(campaign / ("server-warm-process.csv" if warm else f"server-r{rep}-process.csv")), start, end)
    client = rows(campaign / f"client-r{rep}-measured-process.csv")
    def mx(data, key): return max((num(r, key) for r in data), default=0)
    result = {
        "serverMetricSamples": len(metrics), "serverProcessSamples": len(process), "clientProcessSamples": len(client),
        "serverMaxHeapUsedBytes": int(mx(metrics,"heapUsedBytes")), "serverMaxHeapCommittedBytes": int(mx(metrics,"heapCommittedBytes")),
        "serverHeapMaxBytes": int(mx(metrics,"heapMaxBytes")), "serverMaxCacheResidentBytes": int(mx(metrics,"cacheResidentBytes")),
        "serverMaxSessionsActive": int(mx(metrics,"sessionsActive")), "serverMaxDiskQueued": int(mx(metrics,"diskQueued")),
        "serverMaxTileReadWaiting": int(mx(metrics,"tileReadWaiting")), "serverMaxTransientReservedBytes": int(mx(metrics,"transientReservedBytes")),
        "serverMaxRssBytes": int(mx(process,"rssBytes")), "serverMaxCpuPercent": mx(process,"cpuPercent"),
        "clientMaxRssBytes": int(mx(client,"rssBytes")), "clientMaxCpuPercent": mx(client,"cpuPercent")}
    for src, dst in (("cacheHits","cacheHitsDelta"),("cacheMisses","cacheMissesDelta"),("cacheEvictions","cacheEvictionsDelta"),
                     ("tileReadRejected","tileReadRejectedDelta"),("transientRejections","transientRejectionsDelta"),
                     ("releaseTimeouts","releaseTimeoutsDelta"),("writeProgressTimeouts","writeProgressTimeoutsDelta")):
        result[dst] = max(0, int(num(metrics[-1],src)-num(metrics[0],src))) if metrics else 0
    denom = result["cacheHitsDelta"] + result["cacheMissesDelta"]
    result["cacheHitRate"] = result["cacheHitsDelta"] / denom if denom else None
    return result


def main():
    if len(sys.argv) != 2: raise SystemExit("Usage: summarize-campaign.py <campaign-dir>")
    campaign = Path(sys.argv[1]); cp = props(campaign / "campaign.properties"); warm = cp.get("cache") == "warm"
    summaries = []
    for path in sorted(campaign.glob("runs/*/summary.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        if "-r0-warmup" not in data.get("runId", ""): summaries.append(data)
    out_rows = []; pooled = {k: [] for k in ("scheduleLag","plan","firstUseful","lastTile","done")}
    totals = {k: 0 for k in ("viewsSent","viewsCompleted","incompleteViews","tiles","jpegBytes","lupaBinaryBytes","controlBytesIn","controlBytesOut","obsoleteTiles","obsoleteJpegBytes","releasesSent","errors","failedClients")}
    for index, data in enumerate(summaries, 1):
        run_id = data.get("runId", ""); rep = index
        try: rep = int(run_id.split("-r",1)[1].split("-",1)[0])
        except (ValueError, IndexError): pass
        meta = props(campaign / f"run-r{rep}-measured.properties")
        res = resource(campaign, rep, time_s(meta.get("startedAt")), time_s(meta.get("endedAt")), warm)
        lat = latency(data)
        for k, vals in lat.items(): pooled[k].extend(vals)
        for k in totals: totals[k] += data.get(k, 0)
        s = {k: stat(v) for k,v in lat.items()}
        app_bytes = data.get("lupaBinaryBytes",0)+data.get("controlBytesIn",0)+data.get("controlBytesOut",0)
        row = {"repetition":rep,"runId":run_id,"seed":data.get("config",{}).get("seed"),
               "successfulClients":data.get("successfulClients"),"failedClients":data.get("failedClients"),
               "viewsSent":data.get("viewsSent"),"viewsCompleted":data.get("viewsCompleted"),"incompleteViews":data.get("incompleteViews"),
               "tiles":data.get("tiles"),"jpegBytes":data.get("jpegBytes"),"applicationBytes":app_bytes,
               "obsoleteTiles":data.get("obsoleteTiles"),"obsoleteJpegBytes":data.get("obsoleteJpegBytes"),
               "obsoleteTileRatio":data.get("obsoleteTiles",0)/data.get("tiles",1) if data.get("tiles",0) else 0,
               "obsoleteByteRatio":data.get("obsoleteJpegBytes",0)/data.get("jpegBytes",1) if data.get("jpegBytes",0) else 0,
               "errors":data.get("errors",0)}
        for k in ("scheduleLag","plan","firstUseful","lastTile","done"):
            row[k+"N"] = s[k]["n"]; row[k+"MedianMs"] = s[k]["medianMs"]; row[k+"P95Ms"] = s[k]["p95Ms"]
        row.update(res); out_rows.append(row)
    if not out_rows: raise SystemExit("no measured summary.json files found")
    with (campaign/"runs.csv").open("w",newline="",encoding="utf-8") as f:
        w=csv.DictWriter(f,fieldnames=list(out_rows[0].keys())); w.writeheader(); w.writerows(out_rows)
    aggregate = {"schemaVersion":2,"campaignDir":str(campaign),"commit":cp.get("commit"),"mode":cp.get("mode"),"cache":cp.get("cache"),
                 "clients":int(cp.get("clients","0")),"scenario":cp.get("scenario"),"imageId":cp.get("imageId"),"repetitionsFound":len(out_rows),**totals,
                 "obsoleteTileRatio":totals["obsoleteTiles"]/totals["tiles"] if totals["tiles"] else 0,
                 "obsoleteByteRatio":totals["obsoleteJpegBytes"]/totals["jpegBytes"] if totals["jpegBytes"] else 0,
                 "latency":{k:stat(v) for k,v in pooled.items()},
                 "runVariation":{"firstUsefulMedianAcrossRunMediansMs":statistics.median([r["firstUsefulMedianMs"] for r in out_rows if r["firstUsefulMedianMs"] is not None]),
                    "doneMedianAcrossRunMediansMs":statistics.median([r["doneMedianMs"] for r in out_rows if r["doneMedianMs"] is not None]),
                    "jpegBytesMedianPerRun":statistics.median([r["jpegBytes"] for r in out_rows]),
                    "applicationBytesMedianPerRun":statistics.median([r["applicationBytes"] for r in out_rows])},
                 "resources":{"serverMaxHeapUsedBytes":max(r["serverMaxHeapUsedBytes"] for r in out_rows),"serverMaxRssBytes":max(r["serverMaxRssBytes"] for r in out_rows),
                    "serverMaxCacheResidentBytes":max(r["serverMaxCacheResidentBytes"] for r in out_rows),"serverMaxSessionsActive":max(r["serverMaxSessionsActive"] for r in out_rows),
                    "serverMaxDiskQueued":max(r["serverMaxDiskQueued"] for r in out_rows),"serverMaxTileReadWaiting":max(r["serverMaxTileReadWaiting"] for r in out_rows),
                    "serverMaxTransientReservedBytes":max(r["serverMaxTransientReservedBytes"] for r in out_rows),"clientMaxRssBytes":max(r["clientMaxRssBytes"] for r in out_rows)},
                 "cacheMetrics":{"hits":sum(r["cacheHitsDelta"] for r in out_rows),"misses":sum(r["cacheMissesDelta"] for r in out_rows),"evictions":sum(r["cacheEvictionsDelta"] for r in out_rows)},
                 "measurementScope":{"firstUseful":"VIEW->first TILE for same epoch; reception, not Canvas render","done":"VIEW->DONE; protocol completion, not visible render","cache":"LUPA application cache only; OS page cache not flushed"},
                 "percentileMethod":"nearest-rank"}
    (campaign/"aggregate.json").write_text(json.dumps(aggregate,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(aggregate,indent=2))
    missing_metrics=any(r["serverMetricSamples"]==0 for r in out_rows)
    return 1 if totals["errors"] or totals["failedClients"] or missing_metrics else 0


if __name__ == "__main__": raise SystemExit(main())
