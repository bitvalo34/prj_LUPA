#!/usr/bin/env python3
import argparse
import csv
import os
import time
from datetime import datetime, timezone
from pathlib import Path


def args():
    p = argparse.ArgumentParser()
    p.add_argument("--pid", type=int, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--interval-ms", type=int, default=200)
    return p.parse_args()


def status(pid):
    result = {}
    with open(f"/proc/{pid}/status", encoding="utf-8") as f:
        for line in f:
            if ":" in line:
                k, v = line.split(":", 1)
                result[k] = v.strip()
    return result


def kb(values, key):
    parts = values.get(key, "").split()
    return int(parts[0]) * 1024 if parts and parts[0].isdigit() else 0


def proc_io(pid):
    result = {}
    try:
        with open(f"/proc/{pid}/io", encoding="utf-8") as f:
            for line in f:
                k, v = line.split(":", 1)
                result[k.strip()] = int(v.strip())
    except (FileNotFoundError, PermissionError, ProcessLookupError):
        pass
    return result


def stat_ticks(pid):
    raw = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
    tail = raw[raw.rfind(")") + 2:].split()
    return int(tail[11]), int(tail[12])


def main():
    a = args()
    if a.interval_ms < 50:
        raise SystemExit("--interval-ms must be >= 50")
    a.out.parent.mkdir(parents=True, exist_ok=True)
    hz = os.sysconf(os.sysconf_names["SC_CLK_TCK"])
    prev_ticks = prev_ns = None
    fields = ["wallTime", "monotonicNanos", "pid", "rssBytes", "vmSizeBytes", "threads",
              "cpuPercent", "userTicks", "systemTicks", "readBytes", "writeBytes", "syscr", "syscw"]
    with a.out.open("x", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        while Path(f"/proc/{a.pid}").exists():
            now = time.monotonic_ns()
            try:
                st = status(a.pid)
                user, system = stat_ticks(a.pid)
            except (FileNotFoundError, ProcessLookupError):
                break
            io = proc_io(a.pid)
            current = user + system
            cpu = 0.0
            if prev_ticks is not None and prev_ns is not None and now > prev_ns:
                cpu_seconds = (current - prev_ticks) / hz
                cpu = 100.0 * cpu_seconds / ((now - prev_ns) / 1_000_000_000)
            w.writerow({
                "wallTime": datetime.now(timezone.utc).isoformat(), "monotonicNanos": now, "pid": a.pid,
                "rssBytes": kb(st, "VmRSS"), "vmSizeBytes": kb(st, "VmSize"),
                "threads": int(st.get("Threads", "0") or 0), "cpuPercent": f"{cpu:.3f}",
                "userTicks": user, "systemTicks": system, "readBytes": io.get("read_bytes", 0),
                "writeBytes": io.get("write_bytes", 0), "syscr": io.get("syscr", 0), "syscw": io.get("syscw", 0)
            })
            f.flush()
            prev_ticks, prev_ns = current, now
            time.sleep(a.interval_ms / 1000)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
