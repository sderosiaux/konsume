#!/usr/bin/env python3
"""Capture warmed JMH CPU/allocation profiles and interactive flame graphs.

Requires a previously compiled checkout and a local async-profiler distribution.
Profiled timings are diagnostics only and must not enter the measurement campaign.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def summarize(folder):
    stacks = []
    for line in (folder / "cpu.collapsed").read_text().splitlines():
        stack, count = line.rsplit(" ", 1)
        stacks.append((stack.split(";"), int(count)))
    workers = [(s, n) for s, n in stacks if any("OptimizationBench.javaBatch" in f for f in s)]
    count = sum(n for _, n in workers)
    if not count:
        raise RuntimeError("No benchmark worker samples in CPU profile")
    leaf, methods = collections.Counter(), collections.Counter()
    for frames, value in workers:
        leaf[frames[-1]] += value
        names = {re.sub(r"(?::\d+)?_\[[^]]+\]$", "", f) for f in frames}
        for name in names:
            methods[name] += value
    allocations = collections.Counter()
    for line in (folder / "alloc.collapsed").read_text().splitlines():
        stack, amount = line.rsplit(" ", 1)
        if "OptimizationBench.javaBatch" in stack:
            allocations[stack.split(";")[-1]] += int(amount)
    write(folder / "summary.json", {
        "all_cpu_samples": sum(n for _, n in stacks), "java_batch_cpu_samples": count,
        "self_top": [{"frame": f, "samples": n, "worker_percent": 100 * n / count}
                     for f, n in leaf.most_common(30)],
        "inclusive_methods": [{"method": f, "samples": n, "worker_percent": 100 * n / count}
                              for f, n in methods.most_common() if f.startswith(("konsume/", "scala/"))],
        "allocation_bytes_estimated": dict(allocations.most_common()),
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkout", type=Path, required=True)
    parser.add_argument("--profiler", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sizes", type=int, nargs="+", default=[10000, 1000000])
    parser.add_argument("--convert-only", action="store_true", help="Convert an existing captured campaign")
    args = parser.parse_args()
    checkout, output, profiler = args.checkout.resolve(), args.output.resolve(), args.profiler.resolve()
    libraries = list((profiler / "lib").glob("libasyncProfiler.*"))
    if len(libraries) != 1:
        parser.error("Expected one async-profiler native library")
    library = libraries[0]
    converter = profiler / "bin/jfrconv"
    cp = (checkout / "bench/target/streams/jmh/fullClasspath/_global/streams/export").read_text().strip()
    if not args.convert_only:
        if output.exists() and list(output.iterdir()):
            parser.error("Choose a new empty output directory")
        output.mkdir(parents=True, exist_ok=True)
        manifest = {
            "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=checkout, text=True).strip(),
            "profiler_sha256": hashlib.sha256(library.read_bytes()).hexdigest(),
            "profiler_version": subprocess.check_output([str(profiler / "bin/asprof"), "--version"], text=True).strip(),
            "started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "commands": [],
        }
    else:
        manifest = json.loads((output / "manifest.json").read_text())
    manifest["conversion_commands"] = []
    for size in args.sizes:
        folder = output / str(size)
        if not args.convert_only:
            folder.mkdir()
            options = f"async:libPath={library};event=cpu;alloc=1m;interval=1000000;output=jfr;dir={folder};verbose=true"
            cmd = ["java", "-cp", cp, "org.openjdk.jmh.Main", "-f", "1", "-wi", "6", "-i", "5",
                   "-p", f"batchSize={size}", "-p", "engine=optimized", "-prof", options,
                   "-jvmArgsAppend", "-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints",
                   "-rf", "json", "-rff", str(folder / "jmh.json"), "konsume.bench.OptimizationBench.process"]
            manifest["commands"].append(cmd)
            write(output / "manifest.json", manifest)
            print(f"Profiling {checkout.name}, {size} records", flush=True)
            with (folder / "jmh.log").open("w") as log:
                subprocess.run(cmd, cwd=checkout, stdout=log, stderr=subprocess.STDOUT, check=True)
            rows = json.loads((folder / "jmh.json").read_text())
            if len(rows) != 1 or len(rows[0]["primaryMetric"]["rawData"][0]) != 5:
                raise RuntimeError("Incomplete profiling workload")
        recordings = list(folder.rglob("*.jfr"))
        if len(recordings) != 1:
            raise RuntimeError("Expected exactly one JFR per pool")
        jfr = recordings[0]
        for kind, options in (("cpu", ["--cpu"]), ("alloc", ["--alloc", "--total"])):
            for extension in ("html", "collapsed"):
                cmd = [str(converter), *options, "--lines", "--norm", "--title",
                       f"{checkout.name}: {size} records, {kind}", str(jfr), str(folder / f"{kind}.{extension}")]
                manifest["conversion_commands"].append(cmd)
                subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL)
        cmd = [str(converter), "--cpu", "--bci", "--norm", str(jfr), str(folder / "cpu-bci.collapsed")]
        manifest["conversion_commands"].append(cmd)
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL)
        summarize(folder)
        write(output / "manifest.json", manifest)
        print(f"Captured and converted {size}", flush=True)
    manifest["converted_at" if args.convert_only else "completed_at"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    write(output / "manifest.json", manifest)


if __name__ == "__main__":
    main()
