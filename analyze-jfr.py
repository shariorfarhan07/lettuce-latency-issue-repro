"""Summarizes the JFR recordings in jfr/ into jfr/summary.json.

Usage: python analyze-jfr.py   (needs the JDK's `jfr` tool on PATH)
"""
import json
import re
import subprocess
from collections import Counter
from datetime import datetime
from pathlib import Path

JFR_DIR = Path(__file__).parent / "jfr"
EVENTS = "jdk.GarbageCollection,jdk.GCHeapSummary,jdk.ObjectAllocationSample,jdk.OldObjectSample,jdk.GCConfiguration"


def seconds(duration):
    """Parses ISO-8601 durations such as PT0.0024918S or PT1M2.5S."""
    m = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?", duration)
    h, mins, s = (float(g) if g else 0.0 for g in m.groups())
    return h * 3600 + mins * 60 + s


def timestamp(value):
    # Python 3.11+ parses offsets; trim nanoseconds to microseconds.
    value = re.sub(r"(\.\d{6})\d+", r"\1", value)
    return datetime.fromisoformat(value)


def frames(event):
    trace = event["values"].get("stackTrace") or {}
    return [f"{f['method']['type']['name'].replace('/', '.')}.{f['method']['name']}" for f in trace.get("frames", [])]


def first_lettuce_or_app_frame(stack):
    """First Lettuce or application frame, skipping generated lambda classes."""
    for frame in stack:
        if "$$Lambda" in frame:
            continue
        if frame.startswith("io.lettuce.") or frame.startswith("com.example."):
            return frame
    return stack[0] if stack else "(no stack)"


def summarize(path):
    raw = subprocess.run(["jfr", "print", "--json", "--stack-depth", "30", "--events", EVENTS, str(path)],
                         capture_output=True, text=True, check=True).stdout
    events = json.loads(raw)["recording"]["events"]
    t0 = min(timestamp(e["values"]["startTime"]) for e in events)

    def rel(e):
        return round((timestamp(e["values"]["startTime"]) - t0).total_seconds(), 3)

    heap_after_gc = [
        {"t": rel(e), "usedMiB": round(e["values"]["heapUsed"] / 2**20, 1)}
        for e in events if e["type"] == "jdk.GCHeapSummary" and e["values"]["when"] == "After GC"
    ]
    gcs = [
        {"t": rel(e), "name": e["values"]["name"], "cause": e["values"]["cause"],
         "pauseMs": round(seconds(e["values"]["sumOfPauses"]) * 1000, 2)}
        for e in events if e["type"] == "jdk.GarbageCollection"
    ]

    alloc_by_class, alloc_by_site = Counter(), Counter()
    for e in events:
        if e["type"] == "jdk.ObjectAllocationSample":
            weight = e["values"]["weight"]
            alloc_by_class[e["values"]["objectClass"]["name"]] += weight
            alloc_by_site[first_lettuce_or_app_frame(frames(e))] += weight
    total_alloc = sum(alloc_by_class.values()) or 1

    old_by_type, old_by_site = Counter(), Counter()
    for e in events:
        if e["type"] == "jdk.OldObjectSample":
            obj = e["values"]["object"] or {}
            old_by_type[(obj.get("type") or {}).get("name", "?")] += 1
            old_by_site[first_lettuce_or_app_frame(frames(e))] += 1
    total_old = sum(old_by_type.values()) or 1

    collector = next((e["values"]["youngCollector"] + " / " + e["values"]["oldCollector"]
                      for e in events if e["type"] == "jdk.GCConfiguration"), "?")

    return {
        "t0": t0.isoformat(),
        "collector": collector,
        "heapAfterGc": heap_after_gc,
        "gc": gcs,
        "gcCount": len(gcs),
        "gcTotalPauseMs": round(sum(g["pauseMs"] for g in gcs), 1),
        "gcMaxPauseMs": max((g["pauseMs"] for g in gcs), default=0),
        "fullGcCount": sum(1 for g in gcs if "Full" in g["name"]),
        "allocationByClass": [{"name": n, "share": round(w / total_alloc, 3)} for n, w in alloc_by_class.most_common(6)],
        "allocationBySite": [{"name": n, "share": round(w / total_alloc, 3)} for n, w in alloc_by_site.most_common(6)],
        "oldObjectsByType": [{"name": n, "share": round(c / total_old, 3)} for n, c in old_by_type.most_common(6)],
        "oldObjectsBySite": [{"name": n, "share": round(c / total_old, 3)} for n, c in old_by_site.most_common(6)],
    }


def main():
    summary = {p.stem: summarize(p) for p in sorted(JFR_DIR.glob("*.jfr"))}
    (JFR_DIR / "summary.json").write_text(json.dumps(summary, indent=2))
    for name, s in summary.items():
        last = s["heapAfterGc"][-1]["usedMiB"] if s["heapAfterGc"] else None
        print(f"{name}: {s['collector']}, GCs={s['gcCount']} (full={s['fullGcCount']}), "
              f"total pause={s['gcTotalPauseMs']} ms, max pause={s['gcMaxPauseMs']} ms, last heap after GC={last} MiB")
        print("   top allocation site:", s["allocationBySite"][0] if s["allocationBySite"] else None)
        print("   top old-object site:", s["oldObjectsBySite"][0] if s["oldObjectsBySite"] else None)
        print("   top old-object type:", s["oldObjectsByType"][0] if s["oldObjectsByType"] else None)


if __name__ == "__main__":
    main()
