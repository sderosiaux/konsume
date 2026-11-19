#!/usr/bin/env python3
"""Independent JVMs for the protected distribution guards; never pooled with primary timings."""
import argparse
import importlib.util
import json
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import time

sys.dont_write_bytecode = True
from run_research import git, sha, write


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--autoresearch", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    protocol_path = root / "bench/research/profile-guided-2026-11-18/distribution-protocol.json"
    protocol = json.loads(protocol_path.read_text())
    versions = dict(item.split("=", 1) for item in args.version)
    if "baseline" not in versions:
        parser.error("A baseline checkout is required")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if list(output.iterdir()):
        parser.error("Choose an empty output directory")
    autoresearch = args.autoresearch.resolve()
    if git(autoresearch, "rev-parse", "HEAD") != protocol["evaluator_commit"]:
        parser.error("Autoresearch revision differs from the frozen protocol")
    evaluator = autoresearch / "product/autoresearch/measurement.py"
    if git(autoresearch, "diff", "HEAD", "--", str(evaluator)):
        parser.error("Uncommitted evaluator changes")
    spec = importlib.util.spec_from_file_location("research_measurement", evaluator)
    measurement = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(measurement)
    manifest = {
        "protocol": protocol, "protocol_sha256": sha(protocol_path), "evaluator_sha256": sha(evaluator),
        "platform": platform.platform(),
        "java": subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr,
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "versions": {}, "commands": [],
    }
    reference = None
    for name, location in versions.items():
        checkout = Path(location).resolve()
        if git(checkout, "diff", "HEAD", "--", "core/src", "classfile/src", "bench/src", "build.sbt", "project"):
            raise RuntimeError(f"Uncommitted source in {checkout}")
        protected = {p: sha(checkout / p) for p in git(checkout, "ls-files").splitlines()
                     if p.startswith(("core/src/", "bench/src/", "classfile/src/test/", "project/")) or p == "build.sbt"}
        if reference is not None and protected != reference:
            raise RuntimeError("Benchmark, build or correctness tests differ between versions")
        reference = protected
        manifest["versions"][name] = {
            "root": str(checkout), "commit": git(checkout, "rev-parse", "HEAD"),
            "classpath": (checkout / "bench/target/streams/jmh/fullClasspath/_global/streams/export").read_text().strip(),
            "protected_sha256": protected,
            "production_sha256": {str(p.relative_to(checkout)): sha(p) for p in (checkout / "classfile/src/main").rglob("*.java")},
        }
    rows = []
    names = list(versions)
    for round_number in range(protocol["forks"]):
        for index, shape in enumerate(protocol["shapes"]):
            offset = (round_number + index) % len(names)
            for name in names[offset:] + names[:offset]:
                version = manifest["versions"][name]
                stem = f"round-{round_number + 1}-{shape}-{name}"
                result = output / f"{stem}.json"
                cmd = ["java", "-cp", version["classpath"], "org.openjdk.jmh.Main", "-f", "1",
                       "-wi", str(protocol["warmup"]["iterations"]), "-w", "1s",
                       "-i", str(protocol["measurement"]["iterations"]), "-r", "1s",
                       "-p", f"shape={shape}", "-prof", "gc", "-rf", "json", "-rff", str(result), protocol["benchmark"]]
                manifest["commands"].append({"round": round_number + 1, "version": name, "command": cmd})
                write(output / "manifest.json", manifest)
                print(f"Starting {stem}", flush=True)
                with (output / f"{stem}.log").open("w") as log:
                    subprocess.run(cmd, cwd=version["root"], stdout=log, stderr=subprocess.STDOUT, check=True)
                values = json.loads(result.read_text())
                if len(values) != 1 or values[0]["mode"] != "avgt" or len(values[0]["primaryMetric"]["rawData"][0]) != protocol["measurement"]["iterations"]:
                    raise RuntimeError(f"Incomplete measurements in {result}")
                row = values[0]
                if row["primaryMetric"]["scoreUnit"] != "s/op":
                    raise RuntimeError("Unexpected metric units")
                row.update(version=name, campaignRound=round_number + 1)
                rows.append(row)
                write(output / "results.json", rows)
                comparisons = {}
                for case in protocol["shapes"]:
                    groups = {key: [] for key in versions}
                    for r in rows:
                        if r["params"]["shape"] == case:
                            groups[r["version"]].append({
                                "metrics": {"seconds_per_300m": statistics.mean(r["primaryMetric"]["rawData"][0])},
                                "units": {"seconds_per_300m": "seconds"},
                                "checks": {"setup_identities_offsets_and_output_count_passed": True},
                            })
                    comparisons[case] = {key: measurement.compare(groups["baseline"], samples, [protocol["metric"]])
                                         for key, samples in groups.items() if key != "baseline"}
                write(output / "comparison.json", comparisons)
                print(f"Completed {stem}: {row['primaryMetric']['score']:.6f} s/op", flush=True)
    manifest["completed_at"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    write(output / "manifest.json", manifest)


if __name__ == "__main__":
    main()
