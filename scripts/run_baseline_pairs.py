#!/usr/bin/env python3
"""Alternate frozen JVM builds; keep profiler-free timing runs and pair identities."""
import argparse
import fcntl
import json
from privacy import safe_json, redact
import os
import platform
from pathlib import Path
import subprocess
import sys
import time
from datetime import datetime, timezone

from prepare_baseline_build import clean_environment, sha, verify_build
from summarize_baseline_pairs import validate_policy, validate_campaign, validate_definition, read


def capture(command, env=None):
    try:
        r = subprocess.run(list(map(str, command)), env=env, text=True, capture_output=True, timeout=30)
        return dict(returncode=r.returncode, stdout=r.stdout, stderr=r.stderr)
    except (OSError, subprocess.TimeoutExpired) as e:
        return dict(returncode=-1, stdout="", stderr=str(e))


def observations(thermal):
    return {"time": datetime.now(timezone.utc).isoformat(),
            "power": capture(["/usr/bin/pmset", "-g", "batt"]),
            "thermal": capture([thermal]) if thermal else {"returncode": -1, "stdout": "unavailable", "stderr": "not configured"}}


def run_process(command, cwd, env, log, timeout):
    process = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
    start = time.monotonic()
    try:
        while True:
            pid, status, usage = os.wait4(process.pid, os.WNOHANG)
            if pid:
                process.returncode = os.waitstatus_to_exitcode(status)
                return dict(returncode=process.returncode, wall_seconds=time.monotonic() - start,
                            max_rss_bytes=int(usage.ru_maxrss * (1 if sys.platform == "darwin" else 1024)),
                            user_cpu_seconds=usage.ru_utime, system_cpu_seconds=usage.ru_stime)
            if time.monotonic() - start > timeout:
                raise TimeoutError("JVM exceeded declared per-process timeout")
            time.sleep(.2)
    finally:
        if process.returncode is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("policy", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--a", type=Path, required=True)
    parser.add_argument("--b", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--thermal", type=Path)
    args = parser.parse_args()
    policy = json.loads(args.policy.read_text())
    validate_policy(policy)
    builds = {label: path.resolve() for label, path in (("A", args.a), ("B", args.b))}
    manifests = {label: verify_build(path) for label, path in builds.items()}
    if policy["purpose"] == "calibration" and manifests["A"]["identity"] != manifests["B"]["identity"]:
        raise ValueError("A/A calibration requires identical compiled snapshots")
    if policy["purpose"] == "candidate" and manifests["A"]["identity"] == manifests["B"]["identity"]:
        raise ValueError("An identical snapshot cannot establish a candidate improvement")
    java = args.java.resolve()
    for manifest in manifests.values():
        if manifest["java_sha256"] != sha(java):
            raise ValueError("Run with the same JDK used to build both snapshots")
    libs = lambda manifest: {name: digest for name, digest in manifest["files"].items() if name.startswith("lib/")}
    if libs(manifests["A"]) != libs(manifests["B"]):
        raise ValueError("Dependency mismatch; this gate isolates renderer changes")
    harness = lambda m: {n: h for n, h in m["files"].items() if n.startswith("test-classes/com/shangin/fractal/render/Baseline")
                         and Path(n).name.split("$", 1)[0].split(".", 1)[0] in ("BaselineBenchmark", "BaselineFixtures", "BaselineFxBenchmark")}
    if harness(manifests["A"]) != harness(manifests["B"]):
        raise ValueError("Benchmark driver changed between builds")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    (output / ".gitignore").write_text("javafx-cache-*/\n")
    (output / "tmp").mkdir()
    (output / "policy.json").write_text(json.dumps(policy, indent=2) + "\n")
    for label, manifest in manifests.items():
        (output / ("build-" + label + ".json")).write_text(safe_json(manifest, indent=2) + "\n")
    env = clean_environment(java)
    first_manifest = None
    for label, build in builds.items():
        cp = os.pathsep.join(str(build / p) for p in manifests[label]["dependencies"])
        command = [str(java), "-Dbaseline.manifestOnly=true", "-Dbaseline.output=" + str(output / ("preflight-" + label)),
                   "-Dbaseline.sizes=" + policy["sizes"], "-Dbaseline.fixtures=" + policy["fixtures"],
                   "-cp", os.pathsep.join((str(build / "test-classes"), str(build / "classes"), cp)),
                   "com.shangin.fractal.render.BaselineBenchmark"]
        result = capture(command, env)
        (output / ("preflight-" + label + ".json")).write_text(safe_json(result, indent=2) + "\n")
        if result["returncode"] != 0:
            raise ValueError("Fixture preflight failed: " + label)
        manifest = read(output / ("preflight-" + label) / "manifest.csv")
        validate_definition(policy, manifest)
        if first_manifest is not None and first_manifest != manifest:
            raise ValueError("A/B fixtures differ before timing")
        first_manifest = manifest
    settings = capture([java, "-XshowSettings:properties", "-version"], env)
    if settings["returncode"] != 0:
        raise ValueError("Cannot inspect selected JDK")
    tmp = next(line.split("=", 1)[1].strip() for line in settings["stderr"].splitlines() if "java.io.tmpdir =" in line)
    (output / "environment.json").write_text(safe_json({"jdk": settings,
        "system": {"os": platform.system(), "release": platform.release(), "architecture": platform.machine()},
        "hardware": capture(["/usr/sbin/sysctl", "hw.model", "hw.memsize", "hw.ncpu", "machdep.cpu.brand_string"]),
        "controller_sha256": {Path(__file__).name: sha(Path(__file__)), "summarize_baseline_pairs.py": sha(Path(__file__).with_name("summarize_baseline_pairs.py"))},
        "global_lock": str(Path(tmp) / "fractallens-baseline-benchmark.lock"),
        "thermal_during_runs": "unmeasured; observations only before and after each process",
        "publication_scope": policy["runner"], "physical_scanout": "unmeasured"}, indent=2) + "\n")
    # lockf uses the same POSIX record locks as Java FileChannel. Children get a
    # private tmpdir, so their normal lock does not conflict with our campaign lease.
    with (Path(tmp) / "fractallens-baseline-benchmark.lock").open("a+") as lock:
        fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        launches = []
        for pair in range(policy["process_pairs"]):
            order = "AB" if pair % 2 == 0 else "BA"
            for label in order:
                build = builds[label]
                name = f"pair-{pair:02d}-{label}"
                cp = os.pathsep.join(str(build / p) for p in manifests[label]["dependencies"])
                command = [str(java), "-Xmx4g", "-XX:NativeMemoryTracking=off", "-Djava.io.tmpdir=" + str(output / "tmp"),
                           "-Dfractal.aa.profile=false", "-Dfractal.render.diagnostics=false",
                           "-Dfractal.gpu.enabled=false", "-Dfractal.gpu.mandelbrot.enabled=false",
                           "-Dbaseline.output=" + str(output / name), "-Dbaseline.label=" + label,
                           "-Dbaseline.revision=" + manifests[label]["revision"],
                           "-Dbaseline.sizes=" + policy["sizes"], "-Dbaseline.fixtures=" + policy["fixtures"],
                           "-Dbaseline.warmups=" + str(policy["warmups"]), "-Dbaseline.runs=" + str(policy["samples"])]
                if policy["runner"] == "headless":
                    command += ["-cp", os.pathsep.join((str(build / "test-classes"), str(build / "classes"), cp)),
                                "com.shangin.fractal.render.BaselineBenchmark"]
                else:
                    command += ["--module-path", str(build / "classes") + os.pathsep + cp,
                                "--patch-module", "com.shangin.fractal=" + str(build / "test-classes"),
                                "--enable-native-access=javafx.graphics,org.lwjgl", "-Djavafx.cachedir=" + str(output / ("javafx-cache-" + name)),
                                "-Dprism.verbose=true", "-Dbaseline.fx.modes=" + policy["modes"],
                                "-m", "com.shangin.fractal/com.shangin.fractal.render.BaselineFxBenchmark"]
                record = dict(process_pair=pair, order=order, label=label, directory=name, command=command,
                              policy_sha256=sha(output / "policy.json"),
                              before=observations(args.thermal))
                (output / (name + "-launch.json")).write_text(safe_json(record, indent=2) + "\n")
                print("Starting " + name + " (order " + order + ")", flush=True)
                with (output / (name + ".log")).open("x") as log:
                    record["result"] = run_process(command, build, env, log, policy["timeout_seconds"])
                record["after"] = observations(args.thermal)
                launches.append(record)
                (output / (name + "-result.json")).write_text(safe_json(record, indent=2) + "\n")
                if record["result"]["returncode"] != 0:
                    raise RuntimeError("Failed JVM: " + name)
        for path in builds.values():
            verify_build(path)
        (output / "launches.json").write_text(safe_json(launches, indent=2) + "\n")
        validate_campaign(output, require_marker=False)
        (output / "CAMPAIGN_COMPLETE").write_text("All scheduled JVMs exited successfully; strict coverage and fingerprint checks passed. This is not a production promotion.\n")
    print(redact(str(output)), flush=True)


if __name__ == "__main__":
    main()
