#!/usr/bin/env python3
"""Build an exact Git revision into a new, self-contained timing snapshot."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
INJECTED = ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "MAVEN_OPTS", "MAVEN_ARGS")


def clean_environment(java):
    env = dict(os.environ)
    for key in INJECTED:
        env.pop(key, None)
    env["JAVA_HOME"] = str(java.resolve().parent.parent)
    env["PATH"] = str(java.parent) + os.pathsep + env.get("PATH", "")
    return env


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def files_digest(root):
    return {str(p.relative_to(root)): sha(p) for p in sorted(root.rglob("*")) if p.is_file()}


def verify_build(directory):
    data = json.loads((directory / "build.json").read_text())
    actual = {str(p.relative_to(directory)): sha(p) for folder in ("classes", "test-classes", "lib")
              for p in sorted((directory / folder).rglob("*")) if p.is_file()}
    if actual != data["files"]:
        raise ValueError("Compiled snapshot changed: " + str(directory))
    if data["version"] != "9.1-build-v1" or hashlib.sha256(json.dumps(actual, sort_keys=True).encode()).hexdigest() != data["identity"]:
        raise ValueError("Invalid compiled snapshot identity")
    expected_libs = sorted((name for name in actual if name.startswith("lib/")), key=lambda n: int(Path(n).name.split("-", 1)[0]))
    if data["dependencies"] != expected_libs:
        raise ValueError("Dependency classpath order changed")
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("revision", help="Explicit local Git revision; no fetch or checkout mutation")
    parser.add_argument("output", type=Path)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--maven", default="mvn")
    parser.add_argument("--working-tree", action="store_true",
                        help="Freeze the current tracked source files, including uncommitted edits")
    args = parser.parse_args()
    java = args.java.absolute()
    revision = subprocess.check_output(["git", "rev-parse", "--verify", args.revision + "^{commit}"], cwd=ROOT, text=True).strip()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    source = output / "source"
    source.mkdir()
    manifest = "benchmarks/BASELINE_FIXTURES.csv"
    if subprocess.run(["git", "cat-file", "-e", revision + ":" + manifest],
                      cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode != 0:
        manifest = "BASELINE_FIXTURES.csv"
    if args.working_tree:
        paths = subprocess.check_output(["git", "ls-files", "-z", "--", "pom.xml", "src", manifest,
                                         "scripts/build-macos-menu"], cwd=ROOT)
        for raw in paths.split(b"\0"):
            if not raw:
                continue
            relative = Path(os.fsdecode(raw))
            path = ROOT / relative
            if path.is_file():
                destination = source / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, destination)
        source_hash = hashlib.sha256(json.dumps(files_digest(source), sort_keys=True).encode()).hexdigest()
        revision += "+worktree-" + source_hash[:16]
    else:
        archive = output / "source.tar"
        archive_paths = ["pom.xml", "src", manifest]
        # Older benchmark revisions predate the native menu build script.
        if subprocess.run(["git", "cat-file", "-e", revision + ":scripts/build-macos-menu"],
                          cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
            archive_paths.append("scripts/build-macos-menu")
        with archive.open("xb") as f:
            subprocess.run(["git", "archive", revision, *archive_paths], cwd=ROOT, stdout=f, check=True)
        with tarfile.open(archive) as tar:
            # Explicit safe subset works on the system Python 3.9 too.
            for member in tar.getmembers():
                path = Path(member.name)
                if path.is_absolute() or ".." in path.parts or not (member.isdir() or member.isfile()):
                    raise ValueError("Unsafe archive member: " + member.name)
            tar.extractall(source)
        archive.unlink()
    env = clean_environment(java)
    command = [args.maven, "-o", "-q", "-DskipTests", "test-compile", "dependency:build-classpath",
               "-Dmdep.includeScope=test", "-Dmdep.outputFile=target/baseline-classpath.txt"]
    with (output / "build.log").open("x") as log:
        subprocess.run(command, cwd=source, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    for name in ("classes", "test-classes"):
        shutil.copytree(source / "target" / name, output / name)
    (output / "lib").mkdir()
    dependencies = []
    for index, path in enumerate((source / "target/baseline-classpath.txt").read_text().strip().split(os.pathsep)):
        destination = output / "lib" / (str(index) + "-" + Path(path).name)
        shutil.copyfile(path, destination)
        dependencies.append(str(destination.relative_to(output)))
    shutil.rmtree(source / "target")
    files = {str(p.relative_to(output)): sha(p) for folder in ("classes", "test-classes", "lib")
             for p in sorted((output / folder).rglob("*")) if p.is_file()}
    identity = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
    data = {"version": "9.1-build-v1", "revision": revision, "files": files, "identity": identity,
            "dependencies": dependencies, "source_sha256": files_digest(source),
            "java": str(java.resolve()), "java_sha256": sha(java),
            "java_version": subprocess.run([str(java), "-version"], env=env, text=True, capture_output=True, check=True).stderr,
            "build_command": command}
    (output / "build.json").write_text(json.dumps(data, indent=2) + "\n")
    verify_build(output)
    print(str(output) + " " + identity)


if __name__ == "__main__":
    main()
