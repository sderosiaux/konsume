#!/usr/bin/env python3
"""Validate long JMH samples and normalize the fixed-work sustained benchmark."""

import argparse
import json
from pathlib import Path
from statistics import mean


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "results", nargs="?", type=Path,
        default=Path(__file__).resolve().parents[1] / "results/scaling/sustained.json",
    )
    parser.add_argument("--records", type=int, default=300_000_000,
                        help="records per invocation; must match SustainedBench")
    parser.add_argument("--min-seconds", type=float, default=1.0)
    args = parser.parse_args()
    results = json.loads(args.results.read_text())
    if not results or args.records <= 0:
        raise SystemExit("Missing results or invalid record count")

    print("| Distinct records | Engine | Seconds / invocation (99.9% CI) | Min–max seconds | µs / pass | ns / record | Allocated B / record |")
    print("|---:|---|---:|---:|---:|---:|---:|")
    all_samples = []
    for result in sorted(results, key=lambda r: (int(r["params"]["batchSize"]), r["params"]["engine"])):
        metric = result["primaryMetric"]
        if metric["scoreUnit"] != "s/op" or result["mode"] != "ss":
            raise SystemExit("Expected unnormalized SingleShotTime seconds per invocation")
        samples = [sample for fork in metric["rawData"] for sample in fork]
        if not samples:
            raise SystemExit("Missing raw timing samples")
        all_samples.extend(samples)
        size = int(result["params"]["batchSize"])
        if args.records % size:
            raise SystemExit("Record count must be divisible by every pool size")
        seconds = metric["score"]
        allocation = result["secondaryMetrics"]["gc.alloc.rate.norm"]["score"]
        print(f"| {size:,} | {result['params']['engine']} | {seconds:.4f} ± {metric['scoreError']:.4f} "
              f"| {min(samples):.4f}–{max(samples):.4f} | {seconds * size / args.records * 1e6:.3f} "
              f"| {seconds / args.records * 1e9:.3f} | {allocation / args.records:.4f} |")

    print("\nFork means (seconds per invocation):")
    for result in results:
        means = ", ".join(f"{mean(fork):.4f}" for fork in result["primaryMetric"]["rawData"])
        print(f"{result['params']}: {means}")
    print(f"\n{len(all_samples)} measured samples; minimum {min(all_samples):.6f} s; "
          f"maximum {max(all_samples):.6f} s.")
    if any(sample < args.min_seconds for sample in all_samples):
        raise SystemExit(f"FAIL: a measured invocation lasted less than {args.min_seconds} s")
    print(f"PASS: every measured invocation lasted at least {args.min_seconds} s.")


if __name__ == "__main__":
    main()
