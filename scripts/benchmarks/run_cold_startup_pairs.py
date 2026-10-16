#!/usr/bin/env python3
"""Measure fresh-process launch to Stage.show and first layout pulse for frozen builds."""

import argparse
import json
import os
from pathlib import Path
import random
import statistics
import subprocess
import time

from prepare_baseline_build import clean_environment, sha, verify_build

PROBE_CLASS = Path("com/shangin/fractal/app/ColdStartupBenchmark.class")
MARKERS = {
    "stage_shown_ms": "FRACTALLENS_STAGE_SHOWN_EPOCH_MS=",
    "first_layout_pulse_ms": "FRACTALLENS_FIRST_LAYOUT_PULSE_EPOCH_MS=",
}


def power_observation():
    result = subprocess.run(["/usr/bin/pmset", "-g", "batt"], text=True,
                            capture_output=True, check=True)
    return {"at_epoch_ms": time.time_ns() / 1_000_000, "pmset": result.stdout}


def read_markers(output):
    result = {}
    for key, prefix in MARKERS.items():
        lines = [line for line in output.splitlines() if line.startswith(prefix)]
        if len(lines) != 1:
            raise ValueError(f"Expected one {prefix} marker, found {len(lines)}")
        result[key] = int(lines[0][len(prefix):])
    if result["first_layout_pulse_ms"] < result["stage_shown_ms"]:
        raise ValueError("First layout pulse preceded Stage.show")
    return result


def bound(ratios):
    rng = random.Random(20260925)
    draws = [statistics.median(rng.choices(ratios, k=len(ratios))) for _ in range(5000)]
    draws.sort()
    return draws[124], draws[4874]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--a", type=Path, required=True)
    parser.add_argument("--b", type=Path, required=True)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--pairs", type=int, default=30)
    parser.add_argument("--max-ratio", type=float, default=1.05)
    args = parser.parse_args()
    if args.pairs < 30 or args.pairs % 2 or args.max_ratio <= 0:
        parser.error("Use an even count of at least 30 pairs and a positive ratio limit")
    builds = {label: path.resolve() for label, path in (("A", args.a), ("B", args.b))}
    manifests = {label: verify_build(path) for label, path in builds.items()}
    if manifests["A"]["identity"] == manifests["B"]["identity"]:
        raise ValueError("Cold-startup candidate comparison requires different builds")
    java = args.java.resolve()
    if any(manifest["java_sha256"] != sha(java) for manifest in manifests.values()):
        raise ValueError("Use the JDK that compiled both frozen builds")
    libraries = lambda manifest: {name: digest for name, digest in manifest["files"].items() if name.startswith("lib/")}
    if libraries(manifests["A"]) != libraries(manifests["B"]):
        raise ValueError("Frozen builds have different dependencies")
    probe = args.probe.resolve()
    probe_file = probe / PROBE_CLASS
    if not probe_file.is_file():
        raise ValueError(f"Missing shared probe class: {probe_file}")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    (output / ".gitignore").write_text("javafx-cache-*/\nhome-*/\n")
    (output / "tmp").mkdir()
    definition = {"builds": {label: manifest["identity"] for label, manifest in manifests.items()},
                  "java_sha256": sha(java), "probe_sha256": sha(probe_file),
                  "runner_sha256": sha(Path(__file__)),
                  "probe_class": str(PROBE_CLASS), "pairs": args.pairs,
                  "max_ratio": args.max_ratio, "confidence": "5000 seeded paired bootstrap medians",
                  "scope": "process launch to Stage.show and first JavaFX layout pulse; no physical scanout"}
    (output / "definition.json").write_text(json.dumps(definition, indent=2) + "\n")
    env = clean_environment(java)
    rows = []
    for pair in range(args.pairs):
        order = "AB" if pair % 2 == 0 else "BA"
        for label in order:
            name = f"pair-{pair:02d}-{label}"
            build = builds[label]
            manifest = manifests[label]
            dependencies = [build / path for path in manifest["dependencies"]]
            home = output / ("home-" + name)
            home.mkdir()
            command = [str(java), "-Xmx4g", "-Dfractal.gpu.enabled=false",
                       "-Duser.home=" + str(home), "-Djava.io.tmpdir=" + str(output / "tmp"),
                       "-Djavafx.cachedir=" + str(output / ("javafx-cache-" + name)),
                       "--enable-native-access=javafx.graphics,org.lwjgl,com.shangin.fractal",
                       "--add-exports=javafx.graphics/com.sun.glass.ui=com.shangin.fractal",
                       "--add-opens=javafx.graphics/com.sun.glass.ui.mac=com.shangin.fractal",
                       "--add-exports=javafx.graphics/com.sun.javafx.stage=com.shangin.fractal",
                       "--add-exports=javafx.graphics/com.sun.javafx.tk=com.shangin.fractal",
                       "--module-path", os.pathsep.join(map(str, [build / "classes", *dependencies])),
                       "--patch-module", "com.shangin.fractal=" + str(probe),
                       "-m", "com.shangin.fractal/com.shangin.fractal.app.ColdStartupBenchmark"]
            print(f"Starting {name} (order {order})", flush=True)
            power_before = power_observation()
            launched_ms = time.time_ns() / 1_000_000
            started = time.monotonic()
            result = subprocess.run(command, cwd=build, env=env, text=True, capture_output=True, timeout=120)
            process_seconds = time.monotonic() - started
            power_after = power_observation()
            (output / (name + ".log")).write_text(result.stdout + "\n--- stderr ---\n" + result.stderr)
            if result.returncode:
                raise RuntimeError(f"Cold-startup JVM failed: {name}: {result.returncode}")
            markers = read_markers(result.stdout)
            row = {"pair": pair, "order": order, "label": label, "launch_epoch_ms": launched_ms,
                   "process_seconds": process_seconds, "power_before": power_before,
                   "power_after": power_after, **markers}
            for key in MARKERS:
                row[key + "_from_launch"] = markers[key] - launched_ms
                if row[key + "_from_launch"] <= 0:
                    raise ValueError(f"Invalid launch boundary for {name}")
            rows.append(row)
            (output / (name + ".json")).write_text(json.dumps(row, indent=2) + "\n")
    for build in builds.values():
        verify_build(build)
    if sha(probe_file) != definition["probe_sha256"]:
        raise ValueError("Probe changed during campaign")
    if sha(Path(__file__)) != definition["runner_sha256"]:
        raise ValueError("Runner changed during campaign")
    results = []
    for key in MARKERS:
        metric = key + "_from_launch"
        ratios = []
        for pair in range(args.pairs):
            a = next(row[metric] for row in rows if row["pair"] == pair and row["label"] == "A")
            b = next(row[metric] for row in rows if row["pair"] == pair and row["label"] == "B")
            ratios.append(b / a)
        low, high = bound(ratios)
        results.append({"metric": metric, "median_ratio": statistics.median(ratios),
                        "ci95_low": low, "ci95_high": high, "max_ratio": args.max_ratio,
                        "verdict": "pass" if high <= args.max_ratio else "fail" if low > args.max_ratio else "inconclusive"})
    summary = {"verdict": "pass" if all(item["verdict"] == "pass" for item in results)
               else "fail" if any(item["verdict"] == "fail" for item in results) else "inconclusive",
               "metrics": results}
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(summary["verdict"], flush=True)


if __name__ == "__main__":
    main()
