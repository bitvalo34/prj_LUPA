#!/usr/bin/env python3
import csv
import json
import sys
from pathlib import Path

EXPECTED=[(m,c,n) for m in ("normal","no-cancel") for c in ("cold","warm") for n in (1,5,20)]


def main():
    if len(sys.argv) not in (3,4):
        print("Usage: validate-matrix.py <matrix-root> <expected-repetitions> [scenario]",file=sys.stderr); return 2
    root=Path(sys.argv[1]); reps_expected=int(sys.argv[2]); scenario=sys.argv[3] if len(sys.argv)==4 else "aggressive"
    rows=[]; failures=0; commits=set(); images=set()
    for mode,cache,clients in EXPECTED:
        dirs=sorted(p for p in root.glob(f"e24-*-{mode}-{cache}-c{clients}-{scenario}") if p.is_dir())
        if len(dirs)!=1:
            rows.append({"scenario":scenario,"mode":mode,"cache":cache,"clients":clients,"campaignDir":"","repetitionsFound":0,"failedClients":"","errors":"","distinctSeeds":0,"serverMetricEvidence":False,"status":f"expected-1-campaign-found-{len(dirs)}"}); failures+=1; continue
        campaign=dirs[0]; agg_path=campaign/"aggregate.json"; runs_path=campaign/"runs.csv"
        if not agg_path.is_file() or not runs_path.is_file():
            rows.append({"scenario":scenario,"mode":mode,"cache":cache,"clients":clients,"campaignDir":str(campaign),"repetitionsFound":0,"failedClients":"","errors":"","distinctSeeds":0,"serverMetricEvidence":False,"status":"missing-aggregate-or-runs"}); failures+=1; continue
        data=json.loads(agg_path.read_text(encoding="utf-8"))
        with runs_path.open(newline="",encoding="utf-8") as f: run_rows=list(csv.DictReader(f))
        reps=data.get("repetitionsFound",0); failed=data.get("failedClients",0); errors=data.get("errors",0)
        seeds={r.get("seed") for r in run_rows if r.get("seed")}; metric_ok=all(int(float(r.get("serverMetricSamples","0") or 0))>0 for r in run_rows)
        if data.get("commit"): commits.add(data["commit"])
        if data.get("imageId"): images.add(data["imageId"])
        cache_stats=data.get("cacheMetrics",{}); resources=data.get("resources",{})
        status="success"
        if reps!=reps_expected: status=f"wrong-repetition-count-{reps}"
        elif len(seeds)!=reps_expected: status=f"seeds-not-independent-{len(seeds)}"
        elif failed or errors: status="contains-system-failures-or-errors"
        elif not metric_ok: status="missing-server-metric-evidence"
        elif cache=="warm" and (int(cache_stats.get("hits",0))<=0 or int(resources.get("serverMaxCacheResidentBytes",0))<=0): status="warm-cache-not-demonstrated"
        elif cache=="cold" and int(cache_stats.get("misses",0))<=0: status="cold-cache-misses-not-observed"
        if status!="success": failures+=1
        rows.append({"scenario":scenario,"mode":mode,"cache":cache,"clients":clients,"campaignDir":str(campaign),"repetitionsFound":reps,"failedClients":failed,"errors":errors,"distinctSeeds":len(seeds),"serverMetricEvidence":metric_ok,"status":status})
    if len(commits)!=1:
        failures+=1; rows.append({"scenario":scenario,"mode":"*","cache":"*","clients":"*","campaignDir":"","repetitionsFound":"","failedClients":"","errors":"","distinctSeeds":"","serverMetricEvidence":"","status":f"non-equivalent-commits-{len(commits)}"})
    if len(images)!=1:
        failures+=1; rows.append({"scenario":scenario,"mode":"*","cache":"*","clients":"*","campaignDir":"","repetitionsFound":"","failedClients":"","errors":"","distinctSeeds":"","serverMetricEvidence":"","status":f"non-equivalent-imageIds-{len(images)}"})
    out=root/"matrix-validation.csv"; fields=["scenario","mode","cache","clients","campaignDir","repetitionsFound","failedClients","errors","distinctSeeds","serverMetricEvidence","status"]
    with out.open("w",newline="",encoding="utf-8") as f: w=csv.DictWriter(f,fieldnames=fields); w.writeheader(); w.writerows(rows)
    print("E24 MATRIX VALIDATION",f"scenario={scenario}",sep="\n")
    for r in rows: print(r["mode"],r["cache"],r["clients"],r["status"])
    print(f"conditionFailures={failures}")
    return 1 if failures else 0


if __name__=="__main__": raise SystemExit(main())
