#!/usr/bin/env python3
"""Alternate Prism pipelines for one frozen build in screen-observed interaction-latency runs."""
import argparse
import fcntl
import json
import os
import platform
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from publication.privacy import safe_json, redact

from prepare_baseline_build import clean_environment, sha, verify_build
from run_baseline_pairs import capture, observations, run_process
from summarize_pipeline_latency import validate_policy, validate_campaign

MAIN = "com.shangin.fractal/com.shangin.fractal.ui.InteractionLatencyBenchmark"
MACOS_ACCESS = ("--add-exports=javafx.graphics/com.sun.glass.ui=com.shangin.fractal",
                "--add-opens=javafx.graphics/com.sun.glass.ui.mac=com.shangin.fractal",
                "--add-exports=javafx.graphics/com.sun.javafx.stage=com.shangin.fractal",
                "--add-exports=javafx.graphics/com.sun.javafx.tk=com.shangin.fractal")


def command(java, build, manifest, output, name, policy, label, screen=True, jfr=None):
    module_path = os.pathsep.join([str(build / "classes")] + [str(build / p) for p in manifest["dependencies"]])
    result = [str(java), "-Xmx2g", "-XX:NativeMemoryTracking=off", "-Djava.io.tmpdir=" + str(output / "tmp"),
              "--module-path", module_path, "--enable-native-access=javafx.graphics,org.lwjgl",
              # The benchmark Application lives in a package the module does not export.
              "--add-exports=com.shangin.fractal/com.shangin.fractal.ui=javafx.graphics",
              # The same native-integration access as the macOS javafx:run profile.
              *(MACOS_ACCESS if platform.system() == "Darwin" else ()),
              "-Djavafx.cachedir=" + str(output / ("javafx-cache-" + name)), "-Dprism.verbose=true",
              "-Dfractal.gpu.enabled=false", "-Dfractal.gpu.mandelbrot.enabled=false",
              "-Dfractal.aa.profile=false", "-Dfractal.render.diagnostics=false",
              "-Dfractal.latency.output=" + str(output / (name + ".csv")),
              "-Dfractal.latency.samples=" + str(policy["samples"]), "-Dfractal.latency.warmup=" + str(policy["warmups"]),
              "-Dfractal.latency.gestures=" + policy["gestures"], "-Dfractal.latency.noScreen=" + str(not screen).lower(),
              *policy["jvm_options"][label]]
    if jfr is not None:
        # JavaFX events are absent from the JDK templates, so enable them explicitly.
        result.append("-XX:StartFlightRecording:filename=" + str(jfr) + ",settings=default,"
                      "+javafx.PulsePhase#enabled=true,+javafx.PulsePhase#threshold=0ms")
    return result + ["-m", MAIN]


def launch(java, build, manifest, output, policy, env, thermal, name, label, **record):
    cmd = command(java, build, manifest, output, name, policy, label, **record.pop("options", {}))
    record.update(label=label, directory=name, command=cmd, policy_sha256=sha(output / "policy.json"), before=observations(thermal))
    print("Starting " + name, flush=True)
    with (output / (name + ".log")).open("x") as log:
        record["result"] = run_process(cmd, build, env, log, policy["timeout_seconds"])
    record["after"] = observations(thermal)
    (output / (name + "-result.json")).write_text(safe_json(record, indent=2) + "\n")
    if record["result"]["returncode"] != 0:
        raise RuntimeError("Failed JVM: " + name)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("policy", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--build", type=Path, required=True, help="Snapshot from prepare_baseline_build.py")
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--thermal", type=Path)
    args = parser.parse_args()
    policy = json.loads(args.policy.read_text())
    validate_policy(policy)
    build = args.build.resolve()
    manifest = verify_build(build)
    java = args.java.resolve()
    if manifest["java_sha256"] != sha(java):
        raise ValueError("Run with the JDK used to build the snapshot")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    (output / ".gitignore").write_text("javafx-cache-*/\ntmp/\n*.jfr\n")
    (output / "tmp").mkdir()
    (output / "policy.json").write_text(json.dumps(policy, indent=2) + "\n")
    (output / "build.json").write_text(safe_json(manifest, indent=2) + "\n")
    env = clean_environment(java)
    settings = capture([java, "-XshowSettings:properties", "-version"], env)
    if settings["returncode"] != 0:
        raise ValueError("Cannot inspect selected JDK")
    tmp = next(line.split("=", 1)[1].strip() for line in settings["stderr"].splitlines() if "java.io.tmpdir =" in line)
    (output / "environment.json").write_text(safe_json({"jdk": settings,
        "system": {"os": platform.system(), "release": platform.release(), "architecture": platform.machine()},
        "hardware": capture(["/usr/sbin/sysctl", "hw.model", "hw.memsize", "hw.ncpu", "machdep.cpu.brand_string"]),
        "controller_sha256": {Path(__file__).name: sha(Path(__file__)),
                              "summarize_pipeline_latency.py": sha(Path(__file__).with_name("summarize_pipeline_latency.py"))},
        "thermal_during_runs": "unmeasured; observations only before and after each process",
        "physical_scanout": "unmeasured; screen capture is a compositor proxy"}, indent=2) + "\n")
    # Share the timing lease with the other baseline drivers.
    with (Path(tmp) / "fractallens-baseline-benchmark.lock").open("a+") as lock:
        fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        common = (java, build, manifest, output, policy, env, args.thermal)
        launches = []
        for pair in range(policy["process_pairs"]):
            order = "AB" if pair % 2 == 0 else "BA"
            for label in order:
                launches.append(launch(*common, f"pair-{pair:02d}-{label}", label, process_pair=pair, order=order))
        # JFR perturbs timing, so phase diagnostics run after and apart from timed processes.
        diagnostics = [launch(*common, f"diagnostic-{run:02d}-{label}", label, diagnostic_run=run,
                              options=dict(screen=False, jfr=output / f"diagnostic-{run:02d}-{label}.jfr"))
                       for run in range(policy["jfr_diagnostic_runs"]) for label in ("AB" if run % 2 == 0 else "BA")]
        verify_build(build)
        (output / "launches.json").write_text(safe_json(launches, indent=2) + "\n")
        (output / "diagnostics.json").write_text(safe_json(diagnostics, indent=2) + "\n")
        validate_campaign(output, require_marker=False)
        (output / "CAMPAIGN_COMPLETE").write_text("All scheduled JVMs exited successfully with their declared pipelines. This is not a production promotion.\n")
    print(redact(str(output)), flush=True)


if __name__ == "__main__":
    main()
