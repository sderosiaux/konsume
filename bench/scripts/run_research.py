#!/usr/bin/env python3
"""Compare committed worktrees using one protected JMH benchmark and autoresearch.

Compile and test every worktree beforehand. Example: --version baseline=/path/a
--version numeric=/path/b --phase screen --output /path/results --autoresearch /path/tool
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import time

sys.dont_write_bytecode = True


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(root, *args):
    return subprocess.check_output(["git", *args], cwd=root, text=True).strip()


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", action="append", required=True)
    parser.add_argument("--phase", choices=["screen", "confirmation"], required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--autoresearch", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    protocol_path = root / "bench/research/classfile-2026-11-18/protocol.json"
    protocol = json.loads(protocol_path.read_text())
    settings = protocol[args.phase]
    versions = dict(item.split("=", 1) for item in args.version)
    if "baseline" not in versions or len(versions) < 2:
        parser.error("Include baseline and at least one candidate")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if list(output.iterdir()):
        parser.error("Choose a new, empty output directory")
    autoresearch = args.autoresearch.resolve()
    expected_revision = protocol["evaluator"]["commit"]
    if git(autoresearch, "rev-parse", "HEAD") != expected_revision:
        parser.error("Autoresearch revision differs from the frozen protocol")
    evaluator = autoresearch / protocol["evaluator"]["module"]
    if git(autoresearch, "diff", "HEAD", "--", str(evaluator)):
        parser.error("Autoresearch evaluator has uncommitted changes")
    spec = importlib.util.spec_from_file_location("research_measurement", evaluator)
    measurement = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(measurement)
    manifest = {
        "phase": args.phase, "protocol": protocol, "protocol_sha256": sha(protocol_path),
        "evaluator_sha256": sha(evaluator), "platform": platform.platform(),
        "java": subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr,
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "versions": {}, "commands": [],
    }
    protected_reference = None
    for name, location in versions.items():
        checkout = Path(location).resolve()
        if git(checkout, "diff", "HEAD", "--", "core", "classfile", "bench", "build.sbt", "project"):
            raise RuntimeError(f"Uncommitted implementation in {checkout}")
        protected = {p: sha(checkout / p) for p in git(checkout, "ls-files").splitlines()
                     if p.startswith(("core/src/", "bench/src/", "classfile/src/test/", "project/"))
                     or p == "build.sbt"}
        if protected_reference is not None and protected != protected_reference:
            raise RuntimeError(f"Benchmark, dependencies or correctness tests changed in {checkout}")
        protected_reference = protected
        cp = (checkout / "bench/target/streams/jmh/fullClasspath/_global/streams/export").read_text().strip()
        manifest["versions"][name] = {
            "root": str(checkout), "commit": git(checkout, "rev-parse", "HEAD"), "classpath": cp,
            "protected_sha256": protected,
            "production_sha256": {str(p.relative_to(checkout)): sha(p)
                                  for p in (checkout / "classfile/src/main").rglob("*.java")},
        }
    environment = hashlib.sha256(json.dumps({k: manifest[k] for k in ("platform", "java")}, sort_keys=True).encode()).hexdigest()
    results = []

    def compare():
        comparisons = {}
        for size in settings["sizes"]:
            groups = {name: [] for name in versions}
            for row in results:
                if int(row["params"]["batchSize"]) == size:
                    groups[row["version"]].append({
                        "metrics": {"seconds_per_300m": statistics.mean(row["primaryMetric"]["rawData"][0])},
                        "units": {"seconds_per_300m": "seconds"},
                        "checks": {"jmh_setup_and_output_count_passed": True},
                        "environment_fingerprint": environment,
                    })
            comparisons[str(size)] = {
                name: measurement.compare(groups["baseline"], samples, [protocol["metric"]])
                for name, samples in groups.items() if name != "baseline"
            }
        write(output / "comparison.json", comparisons)

    names = list(versions)
    for round_number in range(settings["forks"]):
        for size_index, size in enumerate(settings["sizes"]):
            offset = (round_number + size_index) % len(names)
            for name in names[offset:] + names[:offset]:
                version = manifest["versions"][name]
                stem = f"round-{round_number + 1}-{size}-{name}"
                result_path = output / f"{stem}.json"
                cmd = ["java", "-cp", version["classpath"], "org.openjdk.jmh.Main",
                       "-f", "1", "-wi", str(settings["warmup"]), "-i", str(settings["iterations"]),
                       "-p", f"batchSize={size}", "-p", f"engine={protocol['engine']}",
                       "-prof", "gc", "-rf", "json", "-rff", str(result_path), protocol["benchmark"]]
                manifest["commands"].append({"round": round_number + 1, "version": name, "command": cmd})
                write(output / "manifest.json", manifest)
                print(f"Starting {stem}", flush=True)
                with (output / f"{stem}.log").open("w") as log:
                    subprocess.run(cmd, cwd=version["root"], stdout=log, stderr=subprocess.STDOUT, check=True)
                rows = json.loads(result_path.read_text())
                if len(rows) != 1 or len(rows[0]["primaryMetric"]["rawData"]) != 1:
                    raise RuntimeError(f"Missing single-fork JMH result: {result_path}")
                row = rows[0]
                samples = row["primaryMetric"]["rawData"][0]
                if len(samples) != settings["iterations"] or min(samples) < 1 or row["primaryMetric"]["scoreUnit"] != "s/op":
                    raise RuntimeError(f"Invalid samples: {result_path}")
                row.update(version=name, campaignRound=round_number + 1)
                results.append(row)
                write(output / "results.json", results)
                compare()
                print(f"Completed {stem}: {statistics.mean(samples):.6f} s/op", flush=True)
    manifest["completed_at"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    write(output / "manifest.json", manifest)
    print((output / "comparison.json").read_text(), flush=True)


if __name__ == "__main__":
    main()
