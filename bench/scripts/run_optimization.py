#!/usr/bin/env python3
"""Run independent JMH JVMs sequentially, rotating engine order between rounds.

First run sbt 'bench/Jmh/compile'. No compilation or other benchmark runs should
overlap this campaign. Each invocation processes 300 million records.
"""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sizes", nargs="+", default=["10000", "100000", "1000000"])
    parser.add_argument("--engines", nargs="+", default=["scala", "restored", "optimized"])
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--warmup", type=int, default=6)
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--jvm-args", default="")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if (output / "manifest.json").exists():
        parser.error("Choose a new output directory; a campaign already exists here")
    cp = (root / "bench/target/streams/jmh/fullClasspath/_global/streams/export").read_text().strip()
    sources = [p for module in ("core", "classfile", "bench")
               for p in (root / module / "src/main").rglob("*") if p.suffix in (".java", ".scala")]
    manifest = {
        "platform": platform.platform(), "startedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "java": subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr,
        "gitBase": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "sourceSha256": {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
        "recordsPerInvocation": 300_000_000, "commands": [],
    }
    results = []
    for round_number in range(args.forks):
        for size_index, size in enumerate(args.sizes):
            offset = (round_number + size_index) % len(args.engines)
            engines = args.engines[offset:] + args.engines[:offset]
            for engine in engines:
                stem = f"round-{round_number + 1}-{size}-{engine}"
                result_path = output / f"{stem}.json"
                cmd = ["java", "-cp", cp, "org.openjdk.jmh.Main",
                       "-f", "1", "-wi", str(args.warmup), "-i", str(args.iterations),
                       "-p", f"batchSize={size}", "-p", f"engine={engine}",
                       "-prof", "gc", "-rf", "json", "-rff", str(result_path),
                       "konsume.bench.OptimizationBench.process"]
                if args.jvm_args:
                    cmd += ["-jvmArgsAppend", args.jvm_args]
                manifest["commands"].append({"round": round_number + 1, "command": cmd})
                (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
                print(f"Starting {stem}", flush=True)
                with (output / f"{stem}.log").open("w") as log:
                    subprocess.run(cmd, cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
                rows = json.loads(result_path.read_text())
                if len(rows) != 1 or len(rows[0]["primaryMetric"]["rawData"][0]) != args.iterations:
                    raise RuntimeError(f"Missing JMH measurements in {result_path}")
                for row in rows:
                    row["campaignRound"] = round_number + 1
                    samples = row["primaryMetric"]["rawData"][0]
                    if min(samples) < 1:
                        raise RuntimeError("Measured invocation shorter than one second")
                    print(f"Completed {stem}: {row['primaryMetric']['score']:.6f} s/op", flush=True)
                results.extend(rows)
                (output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    manifest["completedAt"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
