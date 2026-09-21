#!/usr/bin/env python3
"""Build and smoke-test a self-contained FractalUI runtime and native installer."""

import argparse
import binascii
import hashlib
import json
import os
from pathlib import Path
import platform
import plistlib
import re
import shutil
import struct
import subprocess
import sys
import time
import zipfile
import zlib
import xml.etree.ElementTree as ElementTree


ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "target" / "runtime-package"
APP_MODULE = "com.shangin.fractal/com.shangin.fractal.app.FractalApplication"
NATIVE_SUFFIXES = (".dll", ".dylib", ".jnilib", ".so")
LICENSE_SHA256 = {
    "LWJGL-LICENSE.txt": "8130c6f15e9f961e74c2b197551e60ef9d00521addc5908255be1cdd43a6c90b",
    "MoltenVK-LICENSE.txt": "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
    "OpenJFX-ADDITIONAL_LICENSE_INFO.txt": "a69bce275ba7a3570af6579cb0f55682cd75fedfcd49e0e8e9022270c447c916",
    "OpenJFX-ASSEMBLY_EXCEPTION.txt": "a44eb7b5caf5534c6ef536b21edb40b4d6babf91bf97d9d45596868618b2c6fb",
    "OpenJFX-LICENSE.txt": "4b9abebc4338048a7c2dc184e9f800deb349366bdf28eb23c2677a77b4c87726",
    "OpenJFX-graphics-gcc.md": "589e34f142cdf7d1430daa573e61c2e3dd0330c9eb5a2c00d4a98b3a4450810b",
    "OpenJFX-graphics-jpeg_fx.md": "4d8164326adbb7c6a3dbb65b1979962915e4b6f67eb5885409c80a3f350b322a",
    "OpenJFX-graphics-mesa3d.md": "63f4e6f75caebbccb95d903fb43e46ac7111b3624d0a34f146b276d7d9e7b152",
    "OpenJFX-graphics-pipewire.md": "56a8fb1652c70ac204d13bb52ca4d678162e7d21a97d10209c2a633de8082de0",
    "Shaderc-LICENSE.glslang.txt": "b5a00e94f058edc87e05978329b55730d8689abe61205d9018443d03de4f07da",
    "Shaderc-LICENSE.spirv-tools.txt": "47e20ce182bc68fab1a0cfb129b03c326aaf4f2b6b7905aa9d376d31018bd29f",
    "Shaderc-LICENSE.txt": "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4",
}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(command, **kwargs):
    printable = " ".join(str(part) for part in command)
    print(f"+ {printable}", flush=True)
    return subprocess.run([str(part) for part in command], check=True, **kwargs)


def java_tool(name):
    java_home = os.environ.get("JAVA_HOME")
    executable = name + (".exe" if os.name == "nt" else "")
    if java_home:
        candidate = Path(java_home) / "bin" / executable
        if candidate.is_file():
            return candidate
    found = shutil.which(executable)
    if not found:
        raise RuntimeError(f"Cannot find {executable}; set JAVA_HOME to a full JDK 25")
    return Path(found)


def require_jdk_25(jpackage):
    version = subprocess.check_output([jpackage, "--version"], text=True).strip()
    if version.split(".", 1)[0] != "25":
        raise RuntimeError(f"Packaging requires JDK 25, found jpackage {version}")
    return version


def maven_command():
    wrapper = ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    if wrapper.is_file():
        return wrapper
    executable = "mvn.cmd" if os.name == "nt" else "mvn"
    found = shutil.which(executable)
    if found:
        return Path(found)
    raise RuntimeError("Cannot find the Maven Wrapper or a Maven executable on PATH")


def build_project(classpath_file):
    run([
        maven_command(), "--batch-mode", "--no-transfer-progress",
        "-DskipTests", "-Dfractal.gpu.enabled=false", "clean", "package",
        "dependency:build-classpath", "-DincludeScope=runtime",
        f"-Dmdep.outputFile={classpath_file}",
    ], cwd=ROOT)


def is_native_jar(path):
    return "-natives-" in path.name


def is_modular_jar(path):
    with zipfile.ZipFile(path) as archive:
        return any(name == "module-info.class"
                   or (name.startswith("META-INF/versions/") and name.endswith("/module-info.class"))
                   for name in archive.namelist())


def stage_modules(classpath, destination):
    destination.mkdir(parents=True)
    module_jars = []
    native_jars = []
    for dependency in classpath:
        if is_native_jar(dependency):
            native_jars.append(dependency)
        elif is_modular_jar(dependency):
            module_jars.append(dependency)
        elif not dependency.name.startswith("javafx-"):
            raise RuntimeError(f"Runtime dependency is not modular: {dependency.name}")

    application = next((ROOT / "target").glob("fractal-ui-*.jar"), None)
    if application is None:
        raise RuntimeError("Maven did not produce the application JAR")
    for source in [application, *module_jars]:
        shutil.copy2(source, destination / source.name)
    return application, module_jars, native_jars


def extract_natives(native_jars, destination):
    destination.mkdir(parents=True, exist_ok=True)
    extracted = []
    for source in native_jars:
        with zipfile.ZipFile(source) as archive:
            for member in archive.infolist():
                if not member.is_dir() and member.filename.lower().endswith(NATIVE_SUFFIXES):
                    target = destination / Path(member.filename).name
                    contents = archive.read(member)
                    if target.exists() and target.read_bytes() != contents:
                        raise RuntimeError(f"Conflicting native library: {target.name}")
                    target.write_bytes(contents)
                    extracted.append(target)
    if not extracted:
        raise RuntimeError("No LWJGL native libraries were extracted")
    return sorted(set(extracted))


def png_chunk(kind, contents):
    return (struct.pack(">I", len(contents)) + kind + contents
            + struct.pack(">I", binascii.crc32(kind + contents) & 0xffffffff))


def paeth(left, above, upper_left):
    estimate = left + above - upper_left
    distances = (abs(estimate - left), abs(estimate - above), abs(estimate - upper_left))
    return (left, above, upper_left)[distances.index(min(distances))]


def resize_rgba_png(data, size=256):
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise RuntimeError("Windows icon source is not a PNG")
    offset = 8
    compressed = bytearray()
    width = height = None
    while offset < len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        kind = data[offset + 4:offset + 8]
        contents = data[offset + 8:offset + 8 + length]
        offset += 12 + length
        if kind == b"IHDR":
            width, height, depth, color, compression, filtering, interlace = struct.unpack(
                ">IIBBBBB", contents)
            if (depth, color, compression, filtering, interlace) != (8, 6, 0, 0, 0):
                raise RuntimeError("Windows icon source must be non-interlaced 8-bit RGBA PNG")
        elif kind == b"IDAT":
            compressed.extend(contents)
        elif kind == b"IEND":
            break
    if not width or not height:
        raise RuntimeError("Windows icon source has no valid IHDR")

    encoded = zlib.decompress(compressed)
    stride = width * 4
    rows = []
    previous = bytearray(stride)
    position = 0
    for _ in range(height):
        filter_type = encoded[position]
        position += 1
        filtered = encoded[position:position + stride]
        position += stride
        row = bytearray(stride)
        for index, value in enumerate(filtered):
            left = row[index - 4] if index >= 4 else 0
            above = previous[index]
            upper_left = previous[index - 4] if index >= 4 else 0
            predictor = {0: 0, 1: left, 2: above, 3: (left + above) // 2,
                         4: paeth(left, above, upper_left)}.get(filter_type)
            if predictor is None:
                raise RuntimeError(f"Unsupported PNG filter: {filter_type}")
            row[index] = (value + predictor) & 0xff
        rows.append(row)
        previous = row

    resized = bytearray()
    for target_y in range(size):
        source = rows[target_y * height // size]
        resized.append(0)
        for target_x in range(size):
            source_x = target_x * width // size * 4
            resized.extend(source[source_x:source_x + 4])
    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", ihdr)
            + png_chunk(b"IDAT", zlib.compress(resized, 9)) + png_chunk(b"IEND", b""))


def create_windows_icon(png, destination):
    data = resize_rgba_png(png.read_bytes())
    header = struct.pack("<HHH", 0, 1, 1)
    directory = struct.pack("<BBBBHHII", 0, 0, 0, 0, 1, 32, len(data), 22)
    destination.write_bytes(header + directory + data)


def stage_license_materials(destination):
    source = ROOT / "packaging"
    destination.mkdir(parents=True)
    staged = []
    inventory = source / "THIRD-PARTY-LICENSES.txt"
    shutil.copy2(inventory, destination / inventory.name)
    staged.append(destination / inventory.name)
    for name, expected_hash in LICENSE_SHA256.items():
        license_file = source / "licenses" / name
        if not license_file.is_file():
            raise RuntimeError(f"Required license file is missing: {name}")
        actual_hash = sha256(license_file)
        if actual_hash != expected_hash:
            raise RuntimeError(
                f"License file checksum mismatch for {name}: {actual_hash}")
        shutil.copy2(license_file, destination / name)
        staged.append(destination / name)
    return staged


def git_value(*arguments):
    return subprocess.check_output(["git", *arguments], cwd=ROOT, text=True).strip()


def dependency_records(classpath):
    return [{"file": path.name, "sha256": sha256(path)} for path in sorted(classpath)]


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def project_version():
    root = ElementTree.parse(ROOT / "pom.xml").getroot()
    namespace = root.tag.removesuffix("project")
    value = root.findtext(f"{namespace}version")
    if value is None:
        raise RuntimeError("pom.xml does not declare a project version")
    return value.split("-", 1)[0]


def build_provenance(version, jpackage_version, application, dependencies, natives, licenses):
    return {
        "schema": "fractalui-runtime-provenance-v1",
        "application_version": version,
        "revision": git_value("rev-parse", "HEAD"),
        "working_tree_dirty": bool(git_value("status", "--porcelain")),
        "platform": platform.platform(),
        "machine": platform.machine(),
        "jdk": jpackage_version,
        "application": {"file": application.name, "sha256": sha256(application)},
        "dependencies": dependency_records(dependencies),
        "native_libraries": [{"file": path.name, "sha256": sha256(path)} for path in natives],
        "license_files": [{"file": path.name, "sha256": sha256(path)} for path in licenses],
        "cpu_default": True,
    }


def app_executable(image):
    if sys.platform == "darwin":
        return image / "Contents" / "MacOS" / "FractalUI"
    return image / "FractalUI.exe"


def validate_image(image, report):
    executable = app_executable(image)
    if not executable.is_file():
        raise RuntimeError(f"Packaged launcher is missing: {executable}")
    if sys.platform == "darwin":
        info = plistlib.loads((image / "Contents" / "Info.plist").read_bytes())
        if info.get("CFBundleIdentifier") != "com.shangin.fractal":
            raise RuntimeError("Unexpected macOS bundle identifier")
    command = [executable, f"--package-smoke-test={report.resolve()}"]
    environment = dict(os.environ)
    smoke_cache = TARGET / "javafx-cache"
    smoke_cache.mkdir(parents=True, exist_ok=True)
    existing_options = environment.get("JAVA_TOOL_OPTIONS", "").strip()
    cache_option = f"-Djavafx.cachedir={smoke_cache.resolve().as_posix()}"
    environment["JAVA_TOOL_OPTIONS"] = " ".join(
        option for option in (existing_options, quote_java_tool_option(cache_option)) if option)
    run(command, cwd=ROOT, timeout=120, env=environment)
    values = dict(line.split("=", 1) for line in report.read_text(encoding="utf-8").splitlines())
    expected = {"status": "ok", "cpu_fallback": "available", "cpu_render": "complete",
                "javafx": "available"}
    for key, value in expected.items():
        if values.get(key) != value:
            raise RuntimeError(f"Smoke report did not confirm {key}={value}: {values}")
    if sys.platform == "darwin" and values.get("appkit_bridge") != "available":
        raise RuntimeError(f"Smoke report did not confirm the AppKit bridge: {values}")
    return values


def quote_java_tool_option(option):
    return '"' + option.replace("\\", "\\\\").replace('"', '\\"') + '"'


def jpackage_common(jpackage, version, module_path, input_dir, icon):
    options = [
        jpackage, "--name", "FractalUI", "--app-version", version,
        "--vendor", "FractalUI contributors",
        "--description", "Desktop fractal explorer",
        "--module", APP_MODULE, "--module-path", module_path,
        "--input", input_dir, "--icon", icon,
        "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
        "--java-options", "-Dfractal.gpu.enabled=false",
        "--java-options", "-Djava.library.path=$APPDIR/native",
        "--java-options", "--enable-native-access=javafx.graphics,org.lwjgl,com.shangin.fractal",
        "--java-options", "--add-exports=javafx.graphics/com.sun.javafx.stage=com.shangin.fractal",
        "--java-options", "--add-exports=javafx.graphics/com.sun.javafx.tk=com.shangin.fractal",
    ]
    if sys.platform == "darwin":
        options += [
            "--mac-package-identifier", "com.shangin.fractal",
            "--mac-package-name", "FractalUI",
            "--java-options", "--add-exports=javafx.graphics/com.sun.glass.ui=com.shangin.fractal",
            "--java-options", "--add-opens=javafx.graphics/com.sun.glass.ui.mac=com.shangin.fractal",
        ]
    return [str(value) for value in options]


def build_installer(jpackage, image, version, output):
    installer_type = "dmg" if sys.platform == "darwin" else "exe"
    options = [jpackage, "--type", installer_type, "--name", "FractalUI",
               "--app-version", version, "--app-image", image, "--dest", output]
    if os.name == "nt":
        options += ["--win-dir-chooser", "--win-menu", "--win-shortcut"]
    run(options, cwd=ROOT)
    artifacts = sorted(output.glob(f"*.{installer_type}"), key=lambda path: path.stat().st_mtime)
    if not artifacts:
        raise RuntimeError(f"jpackage did not produce a {installer_type} installer")
    return artifacts[-1]


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version")
    parser.add_argument("--app-image-only", action="store_true")
    return parser.parse_args()


def main():
    args = parse_args()
    if not (sys.platform == "darwin" or os.name == "nt"):
        raise RuntimeError("FractalUI runtime packaging supports macOS and Windows")
    version = args.version or project_version()
    if re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", version) is None:
        raise RuntimeError("Package version must contain only numeric dot-separated components")

    jpackage = java_tool("jpackage")
    jpackage_version = require_jdk_25(jpackage)
    shutil.rmtree(TARGET, ignore_errors=True)
    classpath_file = ROOT / "target" / "runtime-classpath.txt"
    build_project(classpath_file)
    classpath = [Path(entry) for entry in classpath_file.read_text().strip().split(os.pathsep)]

    staging = TARGET / "staging"
    module_path = staging / "module-path"
    input_dir = staging / "input"
    application, modules, native_jars = stage_modules(classpath, module_path)
    natives = extract_natives(native_jars, input_dir / "native")
    licenses = stage_license_materials(input_dir / "licenses")

    icon = ROOT / "src/main/resources/com/shangin/fractal/app/icons/FractalUI.icns"
    if os.name == "nt":
        icon = staging / "FractalUI.ico"
        create_windows_icon(
            ROOT / "src/main/resources/com/shangin/fractal/app/icons/fractalui.png", icon)

    provenance = build_provenance(version, jpackage_version, application,
                                  [*modules, *native_jars], natives, licenses)
    write_json(input_dir / "build-provenance.json", provenance)

    image_output = TARGET / "images"
    image_output.mkdir(parents=True)
    common = jpackage_common(jpackage, version, module_path, input_dir, icon)
    run([*common, "--type", "app-image", "--dest", image_output], cwd=ROOT)
    image = image_output / ("FractalUI.app" if sys.platform == "darwin" else "FractalUI")

    smoke_report = TARGET / "smoke-report.properties"
    provenance["smoke"] = validate_image(image, smoke_report)
    provenance["smoke_timestamp_utc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    if args.app_image_only:
        write_json(TARGET / "artifact-provenance.json", provenance)
        print(f"Smoke-tested app image: {image}")
        return

    output = TARGET / "artifacts"
    output.mkdir(parents=True)
    artifact = build_installer(jpackage, image, version, output)
    provenance["artifact"] = {"file": artifact.name, "sha256": sha256(artifact)}
    write_json(TARGET / "artifact-provenance.json", provenance)
    (output / f"{artifact.name}.sha256").write_text(
        f"{provenance['artifact']['sha256']}  {artifact.name}\n", encoding="utf-8")
    print(f"Smoke-tested installer: {artifact}")


if __name__ == "__main__":
    main()
