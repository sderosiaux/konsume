#!/usr/bin/env python3
"""Validate a completed rotated JMH campaign and summarize independent JVMs."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
from statistics import mean


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--iterations", type=int, default=5)
    args = parser.parse_args()
    manifest = json.loads((args.directory / "manifest.json").read_text())
    if "completedAt" not in manifest:
        raise SystemExit("Campaign has not finished")
    rows = json.loads((args.directory / "results.json").read_text())
    records = manifest["recordsPerInvocation"]
    groups = defaultdict(list)
    samples = []
    for row in rows:
        metric = row["primaryMetric"]
        assert row["mode"] == "ss" and metric["scoreUnit"] == "s/op"
        assert len(metric["rawData"]) == 1
        values = metric["rawData"][0]
        assert len(values) == args.iterations
        assert min(values) >= 1
        assert abs(mean(values) - metric["score"]) < 1e-7
        samples.extend(values)
        groups[(int(row["params"]["batchSize"]), row["params"]["engine"])].append(row)
    expected = {(size, engine) for size in (10000, 100000, 1000000)
                for engine in ("scala", "restored", "optimized")}
    assert set(groups) == expected
    data = {}
    for (size, engine), runs in sorted(groups.items()):
        assert len(runs) == args.forks
        assert {r["campaignRound"] for r in runs} == set(range(1, args.forks + 1))
        runs.sort(key=lambda r: r["campaignRound"])
        means = [r["primaryMetric"]["score"] for r in runs]
        item = {
            "seconds": mean(means), "jvmMeans": means,
            "allocationBytesPerRecord": mean(r["secondaryMetrics"]["gc.alloc.rate.norm"]["score"] / records for r in runs),
            "microsecondsPerPass": mean(means) * size / records * 1e6,
        }
        data.setdefault(str(size), {})[engine] = item
    print("| Distinct records | Scala (s) | Restored Java (s) | Optimized Java (s) | Java time reduction |")
    print("|---:|---:|---:|---:|---:|")
    for size, engines in data.items():
        s, r, o = (engines[e]["seconds"] for e in ("scala", "restored", "optimized"))
        print(f"| {int(size):,} | {s:.4f} | {r:.4f} | {o:.4f} | {(1-o/r)*100:.2f}% |")
    print("\nEach time covers 300 million treatments, including iteration and output collection.")
    print("\n| Records | Engine | Means of independent JVMs (s) | Allocated bytes / record |")
    print("|---:|---|---|---:|")
    for size, engines in data.items():
        for engine, item in engines.items():
            print(f"| {int(size):,} | {engine} | " + ", ".join(f"{v:.4f}" for v in item["jvmMeans"])
                  + f" | {item['allocationBytesPerRecord']:.4f} |")
    print(f"\n{len(samples)} measured invocations; minimum {min(samples):.6f} s; maximum {max(samples):.6f} s.")
    summary = {"samples": len(samples), "minSeconds": min(samples), "maxSeconds": max(samples), "datasets": data}
    (args.directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")


if __name__ == "__main__":
    main()
