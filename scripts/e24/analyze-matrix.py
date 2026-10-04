#!/usr/bin/env python3
import csv
import json
import statistics
import sys
from pathlib import Path


def load(campaign):
    agg=json.loads((campaign/"aggregate.json").read_text(encoding="utf-8"))
    with (campaign/"runs.csv").open(newline="",encoding="utf-8") as f: runs=list(csv.DictReader(f))
    return agg,runs


def f(v):
    try: return float(v)
    except (TypeError,ValueError): return 0.0


def fmt(v,d=3): return "—" if v is None else f"{v:.{d}f}"


def main():
    if len(sys.argv) not in (2,3): raise SystemExit("Usage: analyze-matrix.py <matrix-root> [scenario]")
    root=Path(sys.argv[1]); scenario=sys.argv[2] if len(sys.argv)==3 else "aggressive"
    conditions={}
    for campaign in sorted(root.glob(f"e24-*-*-*-c*-{scenario}")):
        if campaign.is_dir() and (campaign/"aggregate.json").is_file():
            agg,runs=load(campaign); key=(agg["mode"],agg["cache"],int(agg["clients"]))
            if key in conditions: raise SystemExit(f"duplicate campaign for {key}")
            conditions[key]=(campaign,agg,runs)
    expected={(m,c,n) for m in ("normal","no-cancel") for c in ("cold","warm") for n in (1,5,20)}
    missing=expected-set(conditions)
    if missing: raise SystemExit(f"matrix incomplete: {sorted(missing)}")

    summary=[]
    for key,(campaign,a,runs) in sorted(conditions.items(),key=lambda x:(x[0][1],x[0][2],x[0][0])):
        mode,cache,clients=key; first=a["latency"]["firstUseful"]; plan=a["latency"]["plan"]; done=a["latency"]["done"]
        summary.append({"scenario":scenario,"mode":mode,"cache":cache,"clients":clients,"repetitions":a["repetitionsFound"],
          "viewsSent":a["viewsSent"],"viewsCompleted":a["viewsCompleted"],"incompleteViews":a["incompleteViews"],
          "firstUsefulN":first["n"],"firstUsefulMedianMs":first["medianMs"],"firstUsefulP95Ms":first["p95Ms"],
          "planMedianMs":plan["medianMs"],"planP95Ms":plan["p95Ms"],"doneN":done["n"],"doneMedianMs":done["medianMs"],"doneP95Ms":done["p95Ms"],
          "jpegBytesMedianPerRun":a["runVariation"]["jpegBytesMedianPerRun"],"applicationBytesMedianPerRun":a["runVariation"]["applicationBytesMedianPerRun"],
          "obsoleteTileRatio":a["obsoleteTileRatio"],"obsoleteByteRatio":a["obsoleteByteRatio"],"errors":a["errors"],"failedClients":a["failedClients"],
          "serverMaxRssBytes":a["resources"]["serverMaxRssBytes"],"serverMaxHeapUsedBytes":a["resources"]["serverMaxHeapUsedBytes"],
          "serverMaxCacheResidentBytes":a["resources"]["serverMaxCacheResidentBytes"],"serverMaxDiskQueued":a["resources"]["serverMaxDiskQueued"],
          "serverMaxTileReadWaiting":a["resources"]["serverMaxTileReadWaiting"],"serverMaxTransientReservedBytes":a["resources"]["serverMaxTransientReservedBytes"],
          "campaignDir":str(campaign)})
    with (root/"matrix-summary.csv").open("w",newline="",encoding="utf-8") as stream:
        w=csv.DictWriter(stream,fieldnames=list(summary[0].keys())); w.writeheader(); w.writerows(summary)

    comparisons=[]
    for cache in ("cold","warm"):
        for clients in (1,5,20):
            _,normal,nruns=conditions[("normal",cache,clients)]; _,base,bruns=conditions[("no-cancel",cache,clients)]
            nby={r["seed"]:r for r in nruns}; bby={r["seed"]:r for r in bruns}; seeds=sorted(set(nby)&set(bby))
            if len(seeds)!=5: raise SystemExit(f"expected 5 paired seeds for {cache}/{clients}, found {len(seeds)}")
            jpeg=[]; app=[]
            for seed in seeds:
                nj=f(nby[seed]["jpegBytes"]); bj=f(bby[seed]["jpegBytes"]); na=f(nby[seed]["applicationBytes"]); ba=f(bby[seed]["applicationBytes"])
                if bj: jpeg.append(100*(bj-nj)/bj)
                if ba: app.append(100*(ba-na)/ba)
            comparisons.append({"scenario":scenario,"cache":cache,"clients":clients,"pairedSeeds":len(seeds),
              "jpegSavingsMedianPct":statistics.median(jpeg) if jpeg else None,"jpegSavingsMinPct":min(jpeg) if jpeg else None,"jpegSavingsMaxPct":max(jpeg) if jpeg else None,
              "applicationSavingsMedianPct":statistics.median(app) if app else None,
              "normalFirstUsefulMedianMs":normal["latency"]["firstUseful"]["medianMs"],"baselineFirstUsefulMedianMs":base["latency"]["firstUseful"]["medianMs"],
              "normalDoneMedianMs":normal["latency"]["done"]["medianMs"],"baselineDoneMedianMs":base["latency"]["done"]["medianMs"],
              "normalObsoleteByteRatio":normal["obsoleteByteRatio"],"baselineObsoleteByteRatio":base["obsoleteByteRatio"],
              "normalIncompleteViews":normal["incompleteViews"],"baselineIncompleteViews":base["incompleteViews"]})
    with (root/"comparisons.csv").open("w",newline="",encoding="utf-8") as stream:
        w=csv.DictWriter(stream,fieldnames=list(comparisons[0].keys())); w.writeheader(); w.writerows(comparisons)
    report={"schemaVersion":1,"scenario":scenario,"conditions":summary,"comparisons":comparisons,
      "visualGoals":{"firstViewUnder1s":"not-evaluable-from-technical-client","visibleRefinementMedianUnder500ms":"not-evaluable-from-technical-client","reason":"E23LoadClient has no Canvas/render timing; use A24 browser evidence."},
      "byteSavingsFormula":"100 * (bytes_baseline - bytes_normal) / bytes_baseline","percentileMethod":"nearest-rank"}
    (root/"matrix-analysis.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")

    lines=[f"# E24 — resultados backend ({scenario})","","Generado desde datos originales. Las latencias son de protocolo/recepción; no son tiempos de Canvas.","",
      "| Caché | Clientes | Variante | Primera TILE mediana/p95 ms | DONE mediana/p95 ms | JPEG mediana/run B | Obsoleto bytes | RSS servidor máx MiB | Errores |",
      "|---|---:|---|---:|---:|---:|---:|---:|---:|"]
    for r in summary:
        lines.append(f"| {r['cache']} | {r['clients']} | {r['mode']} | {fmt(r['firstUsefulMedianMs'])}/{fmt(r['firstUsefulP95Ms'])} | {fmt(r['doneMedianMs'])}/{fmt(r['doneP95Ms'])} | {int(r['jpegBytesMedianPerRun'] or 0)} | {100*f(r['obsoleteByteRatio']):.2f}% | {f(r['serverMaxRssBytes'])/(1024*1024):.2f} | {r['errors']} |")
    lines += ["","## Comparación normal vs sin cancelación","","| Caché | Clientes | Ahorro JPEG mediano | Ahorro app mediano | Obsoleto normal | Obsoleto sin cancelación |","|---|---:|---:|---:|---:|---:|"]
    for r in comparisons:
        lines.append(f"| {r['cache']} | {r['clients']} | {fmt(r['jpegSavingsMedianPct'],2)}% | {fmt(r['applicationSavingsMedianPct'],2)}% | {100*f(r['normalObsoleteByteRatio']):.2f}% | {100*f(r['baselineObsoleteByteRatio']):.2f}% |")
    lines += ["","## Metas visuales","","- Primera vista < 1 s: **no evaluable con el cliente técnico**.","- Refinamiento visible < 500 ms mediana: **no evaluable con el cliente técnico**.","- A24 debe medir navegador/Canvas; no se sustituyen esas metas por VIEW→TILE o VIEW→DONE.","","p95 usa nearest-rank; fallos no reciben latencia cero; `cold` significa caché LUPA fría, no page cache del SO."]
    (root/"BACKEND_RESULTS.md").write_text("\n".join(lines)+"\n",encoding="utf-8")
    print(root/"BACKEND_RESULTS.md")
    return 0


if __name__=="__main__": raise SystemExit(main())
