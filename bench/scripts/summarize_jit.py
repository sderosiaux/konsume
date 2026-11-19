#!/usr/bin/env python3
"""Extract C2 inlining/allocation evidence from -XX:+LogCompilation XML logs."""
import argparse
import gzip
import hashlib
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET


def summarize(path, method_pattern="filterBatch"):
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rb") as stream:
        root = ET.parse(stream).getroot()
    nmethods = {e.get("compile_id"): e for e in root.iter("nmethod")}
    tasks = []
    for task in root.iter("task"):
        if not re.search(method_pattern, task.get("method", "")):
            continue
        native = nmethods.get(task.get("compile_id"))
        if native is None or native.get("compiler") != "c2":
            continue
        classes = {e.get("id"): e.get("name", "") for e in task.iter("klass")}
        methods = {
            e.get("id"): classes.get(e.get("holder"), e.get("holder", "")) + "." + e.get("name", "")
            for e in task.iter("method")
        }
        inlining = []

        def walk(node):
            method = None
            for child in node:
                if child.tag == "call":
                    method = methods.get(child.get("method"), "")
                if child.tag in ("inline_success", "inline_fail") and method and "konsume" in method:
                    event = {"method": method, "decision": child.tag, "reason": child.get("reason")}
                    if event not in inlining:
                        inlining.append(event)
                walk(child)

        walk(task)
        tasks.append({
            "method": task.get("method"),
            "compile_id": task.get("compile_id"),
            "compiled_at_seconds": float(native.get("stamp")),
            "nmethod_size_bytes": int(native.get("size")),
            "inlining": inlining,
            "eliminated_allocations": [classes.get(e.get("type"), e.get("type")) for e in task.iter("eliminate_allocation")],
        })
    return {"source": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "c2_tasks": tasks}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", type=Path, nargs="+")
    parser.add_argument("--json", type=Path, required=True)
    parser.add_argument("--method", default="filterBatch", help="Regex selecting root compiled methods")
    args = parser.parse_args()
    data = [summarize(path, args.method) for path in args.logs]
    args.json.write_text(json.dumps(data, indent=2) + "\n")
    for log in data:
        print(log["source"], "SHA256", log["sha256"])
        # Preserve full evidence in JSON, show the latest compilation of each root method here.
        latest = {}
        for task in sorted(log["c2_tasks"], key=lambda t: t["compiled_at_seconds"]):
            latest[task["method"]] = task
        for task in latest.values():
            print(f"  C2 #{task['compile_id']} at {task['compiled_at_seconds']:.3f}s: {task['method']}")
            for event in task["inlining"]:
                print(f"    {event['method']}: {event['decision']} ({event['reason']})")
            print("    Eliminated allocations:", ", ".join(task["eliminated_allocations"]) or "none")
        print()


if __name__ == "__main__":
    main()
