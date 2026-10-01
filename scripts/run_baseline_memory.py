#!/usr/bin/env python3
"""Run the diagnostic input soak with RSS/thermal observations, NMT and optional JFR."""
import argparse
import csv
import hashlib
import json
from privacy import safe_json, redact
from pathlib import Path
import subprocess
import time
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]


def command(args, timeout=20):
    try:
        r = subprocess.run(list(map(str, args)), cwd=ROOT, text=True, capture_output=True, timeout=timeout)
        return {"returncode": r.returncode, "stdout": r.stdout, "stderr": r.stderr}
    except (OSError, subprocess.TimeoutExpired) as e:
        return {"returncode": -1, "stdout": "", "stderr": str(e)}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("output", type=Path)
    p.add_argument("--java", type=Path, required=True)
    p.add_argument("--seconds", type=int, default=600)
    p.add_argument("--cycles", type=int, default=3)
    p.add_argument("--sizes", default="480x270")
    p.add_argument("--fixtures", default="seahorse-fixed,julia-aa-regular,deep-glitch-aa,direct-deep-reverse,pan-25,resize-then-drag,cancel-direct,cancel-deep")
    p.add_argument("--modes", default="FAST,REFINED")
    p.add_argument("--jfr", action="store_true", help="Allocation attribution run, separate from sustained run")
    p.add_argument("--thermal", type=Path, help="Compiled baseline_thermal.swift probe")
    a = p.parse_args()
    if a.seconds < 1 or a.cycles < 1:
        p.error("Positive seconds/cycles required")
    output = a.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    (output / ".gitignore").write_text("allocation.jfr\nallocation-events.json\njavafx-cache/\n")
    cp = (ROOT / "target/baseline-classpath.txt").read_text().strip()
    java = a.java.absolute()  # Keep the caller's selected JDK, never /usr/bin/java fallback.
    jcmd = java.parent / "jcmd"
    revision = command(["git", "rev-parse", "HEAD"])["stdout"].strip()
    args = [str(java), "-Xmx4g", "-XX:NativeMemoryTracking=summary", "-Xlog:gc*:file=" + str(output / "gc.log") + ":time,uptime,level,tags",
            "--module-path", str(ROOT / "target/classes") + ":" + cp,
            "--patch-module", "com.shangin.fractal=" + str(ROOT / "target/test-classes"),
            "--enable-native-access=javafx.graphics,org.lwjgl", "-Djavafx.cachedir=" + str(output / "javafx-cache"),
            "-Dprism.verbose=true", "-Dfractal.render.diagnostics=true", "-Dbaseline.output=" + str(output / "workload"),
            "-Dbaseline.revision=" + revision, "-Dbaseline.sizes=" + a.sizes,
            "-Dbaseline.fixtures=" + a.fixtures, "-Dbaseline.fx.modes=" + a.modes,
            "-Dbaseline.memory.seconds=" + str(a.seconds), "-Dbaseline.memory.cycles=" + str(a.cycles),
            "-Dbaseline.memory.externalMonitor=true"]
    if a.jfr:
        args += ["-XX:StartFlightRecording=settings=profile,filename=" + str(output / "allocation.jfr") + ",dumponexit=true,maxsize=256m"]
    args += ["-m", "com.shangin.fractal/com.shangin.fractal.render.BaselineMemoryBenchmark"]
    hashes = {}
    for base in (ROOT / "src", ROOT / "scripts"):
        for f in sorted(base.rglob("*")):
            if f.suffix in (".java", ".py", ".swift"):
                hashes[str(f.relative_to(ROOT))] = hashlib.sha256(f.read_bytes()).hexdigest()
    (output / "source-sha256.json").write_text(json.dumps(hashes, indent=2) + "\n")
    environment = {"command": args, "git_status": command(["git", "status", "--short", "--branch"]),
                   "java": command([java, "-version"]), "hardware": command(["/usr/sbin/sysctl", "hw.model", "hw.memsize", "hw.ncpu", "machdep.cpu.brand_string"]),
                   "power": command(["/usr/bin/pmset", "-g", "batt"]), "thermal_notes": command(["/usr/bin/pmset", "-g", "therm"]),
                   "scope": "diagnostic; native tracking and input trace enabled; controls included in process totals"}
    (output / "environment.json").write_text(safe_json(environment, indent=2) + "\n")
    with (output / "launch.log").open("x") as log, (output / "process.csv").open("x", newline="") as f:
        process = subprocess.Popen(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        writer = csv.writer(f, lineterminator="\n")
        writer.writerow(["time", "elapsed_s", "rss_bytes", "cpu_percent", "thermal_state"])
        start = time.monotonic()
        baseline = False
        final = False
        try:
            while process.poll() is None:
                now = time.monotonic()
                ps = command(["/bin/ps", "-o", "rss=,%cpu=", "-p", process.pid])
                fields = ps["stdout"].split()
                rss, cpu = (int(fields[0]) * 1024, float(fields[1])) if len(fields) == 2 else (-1, -1)
                thermal = command([a.thermal]) if a.thermal else {"stdout": "unavailable", "returncode": -1}
                state = thermal["stdout"].strip() if thermal["returncode"] == 0 else "unavailable"
                if state not in ("nominal", "fair", "serious", "critical", "unknown"):
                    state = "unavailable"
                writer.writerow([datetime.now(timezone.utc).isoformat(), round(now - start, 3), rss, cpu, state]); f.flush()
                if not baseline and now - start >= 5:
                    result = command([jcmd, process.pid, "VM.native_memory", "baseline"])
                    (output / "nmt-baseline.json").write_text(safe_json(result, indent=2) + "\n")
                    baseline = result["returncode"] == 0 and "Baseline taken" in result["stdout"]
                    if not baseline:
                        raise RuntimeError("NMT baseline unavailable; see nmt-baseline.json")
                if (output / "workload/MONITOR_READY").exists() and baseline and not final:
                    result = command([jcmd, process.pid, "VM.native_memory", "summary.diff", "scale=KB"])
                    (output / "nmt-final.json").write_text(safe_json(result, indent=2) + "\n")
                    if not baseline or result["returncode"] != 0 or "Total: reserved=" not in result["stdout"]:
                        raise RuntimeError("Final NMT unavailable")
                    (output / "workload/MONITOR_RELEASE").write_text("NMT collected\n")
                    final = True
                if now - start > a.seconds + 1800:
                    raise TimeoutError("Workload exceeded duration plus 30 minute drain allowance")
                time.sleep(2)
            if process.returncode != 0 or not final or not (output / "workload/SUCCESS").exists():
                raise RuntimeError("Workload or monitoring incomplete; inspect launch.log")
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait()
    (output / "MONITOR_SUCCESS").write_text("Process exited successfully; conformance and NMT complete.\n")
    print(redact(str(output)))


if __name__ == "__main__":
    main()
