#!/usr/bin/env python3
"""Run the bounded preparation policy on one frozen build in an active macOS desktop session."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys

from analyze_frame_preparation import read_csv, validate_campaign, validate_manifest, validate_policy
from prepare_baseline_build import clean_environment, sha, verify_build
from run_baseline_pairs import capture, observations, run_process
from summarize_baseline_pairs import require


def launches(policy, build, manifest, java, output):
    classpath = os.pathsep.join([str(build / "classes"), *(str(build / path) for path in manifest["dependencies"])])
    result = []
    for pair in range(policy["process_pairs"]):
        order = "AB" if pair % 2 == 0 else "BA"
        for label in order:
            name = f"pair-{pair:02d}-{label}"
            command = [str(java), "-Xmx4g", "-XX:NativeMemoryTracking=off", "-Djava.io.tmpdir=" + str(output / "tmp"),
                       "-Dfractal.aa.profile=false", "-Dfractal.render.diagnostics=false", "-Dfractal.gpu.enabled=false",
                       "-Dfractal.gpu.mandelbrot.enabled=false", "-Dbaseline.fx.jfr=false",
                       "-Dprism.order=" + policy["pipeline"], "-Dprism.verbose=true",
                       "-Dbaseline.fx.preparation=" + policy["variants"][label], "-Dbaseline.fx.modes=" + policy["modes"],
                       "-Dbaseline.sizes=" + policy["sizes"], "-Dbaseline.fixtures=" + policy["fixtures"],
                       "-Dbaseline.warmups=" + str(policy["warmups"]), "-Dbaseline.runs=" + str(policy["samples"]),
                       "-Dbaseline.output=" + str(output / name), "-Dbaseline.label=" + label,
                       "-Dbaseline.revision=" + manifest["revision"],
                       "--module-path", classpath, "--patch-module", "com.shangin.fractal=" + str(build / "test-classes"),
                       "--enable-native-access=javafx.graphics,org.lwjgl", "-Djavafx.cachedir=" + str(output / (name + "-cache")),
                       "-m", "com.shangin.fractal/com.shangin.fractal.render.BaselineFxBenchmark"]
            result.append(dict(process_pair=pair, order=order, label=label, directory=name, command=command))
    return result


def require_conditions(observed):
    require(observed["power"]["returncode"] == 0 and "Now drawing from 'AC Power'" in observed["power"]["stdout"], "AC power not confirmed")
    require(observed["thermal"]["returncode"] == 0 and observed["thermal"]["stdout"].strip() == "nominal", "Nominal thermal pressure not confirmed")


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def run(policy_path, output, build, java, thermal=None, preflight_only=False):
    policy_path, output, build, java = (Path(path).resolve() for path in (policy_path, output, build, java))
    policy = json.loads(policy_path.read_text())
    validate_policy(policy)
    require(preflight_only or sys.platform == "darwin", "Measured preparation campaigns require macOS")
    require(preflight_only or thermal is not None, "Measured campaigns require a thermal helper")
    manifest = verify_build(build)
    require(manifest["java_sha256"] == sha(java), "Use the JDK recorded in the frozen build")
    require((build / "test-classes/com/shangin/fractal/render/FreshFramePreparationExperiment.class").is_file(),
            "Frozen build does not contain the worker experiment")
    output.mkdir(parents=True, exist_ok=False)
    (output / "tmp").mkdir()
    shutil.copyfile(policy_path, output / "policy.json")
    policy_hash = sha(output / "policy.json")
    write_json(output / "build-before.json", manifest)
    environment = clean_environment(java)
    settings = capture([java, "-XshowSettings:properties", "-version"], environment)
    write_json(output / "jdk-settings.json", settings)
    require(settings["returncode"] == 0, "Cannot inspect selected JDK")
    tmp = next(line.split("=", 1)[1].strip() for line in settings["stderr"].splitlines() if "java.io.tmpdir =" in line)
    preflight = [str(java), "-Dbaseline.manifestOnly=true", "-Dbaseline.output=" + str(output / "preflight"),
                 "-Dbaseline.sizes=" + policy["sizes"], "-Dbaseline.fixtures=" + policy["fixtures"], "-cp",
                 os.pathsep.join([str(build / "test-classes"), str(build / "classes"),
                                 *(str(build / path) for path in manifest["dependencies"])]),
                 "com.shangin.fractal.render.BaselineBenchmark"]
    checked = capture(preflight, environment)
    write_json(output / "preflight.json", checked)
    require(checked["returncode"] == 0, "Headless fixture preflight failed")
    validate_manifest(policy, read_csv(output / "preflight/manifest.csv"))
    plan = launches(policy, build, manifest, java, output)
    for record in plan:
        record["policy_sha256"] = policy_hash
    write_json(output / "plan.json", plan)
    if preflight_only:
        after = verify_build(build)
        write_json(output / "build-after.json", after)
        require(manifest == after and sha(output / "policy.json") == policy_hash, "Frozen inputs changed")
        (output / "PREFLIGHT_COMPLETE").write_text("Headless preflight only; no JavaFX JVMs or measurements were run.\n")
        return

    # The same record lock as the ordinary benchmark runner; children use a private tmpdir.
    import fcntl
    records = []
    with (Path(tmp) / "fractallens-baseline-benchmark.lock").open("a+") as lock:
        fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            for planned in plan:
                record = dict(planned, before=observations(thermal))
                name = record["directory"]
                write_json(output / (name + "-launch.json"), record)
                require_conditions(record["before"])
                print("Starting " + name, flush=True)
                try:
                    with (output / (name + ".log")).open("x") as log:
                        record["result"] = run_process(record["command"], build, environment, log, policy["timeout_seconds"])
                except BaseException as error:
                    record["result"] = dict(returncode=None, error=type(error).__name__)
                    raise
                finally:
                    record["after"] = observations(thermal)
                    records.append(record)
                    write_json(output / "launches.json", records)
                require(record["result"]["returncode"] == 0, "Unsuccessful JVM: " + name)
                require_conditions(record["after"])
                require((output / name / "manifest.csv").read_bytes() == (output / "preflight/manifest.csv").read_bytes(),
                        "Measured fixture manifest differs from the frozen-build preflight")
        finally:
            write_json(output / "build-after.json", verify_build(build))
        require(manifest == json.loads((output / "build-after.json").read_text()), "Frozen build changed")
        require(sha(output / "policy.json") == policy_hash, "Policy changed during campaign")
        validate_campaign(output, require_marker=False)
        (output / "CAMPAIGN_COMPLETE").write_text("All declared JVMs, frozen inputs and paired coverage validated; analysis pending.\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("policy", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--build", type=Path, required=True, help="One immutable prepare_baseline_build.py snapshot for both variants")
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--thermal", type=Path)
    parser.add_argument("--preflight-only", action="store_true", help="Validate the frozen build and Java fixture matrix without starting JavaFX")
    args = parser.parse_args()
    run(args.policy, args.output, args.build, args.java, args.thermal, args.preflight_only)
    print("Headless preflight complete; no measurements" if args.preflight_only else "Campaign complete; run the dedicated analyzer")


if __name__ == "__main__":
    main()
