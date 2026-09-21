"""Contract checks for scripts/package_runtime.py."""

from pathlib import Path
import os
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile


sys.path.insert(0, str(Path(__file__).resolve().parent))
import package_runtime


class PackageRuntimeContractTest(unittest.TestCase):

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def jar(self, name, entries):
        path = self.root / name
        with zipfile.ZipFile(path, "w") as archive:
            for entry, contents in entries.items():
                archive.writestr(entry, contents)
        return path

    def test_recognizes_versioned_and_platform_module_descriptors(self):
        regular = self.jar("regular.jar", {"module-info.class": b"module"})
        multi_release = self.jar(
            "multi.jar", {"META-INF/versions/25/module-info.class": b"module"})
        empty_javafx = self.jar("javafx-controls.jar", {"META-INF/MANIFEST.MF": b""})

        self.assertTrue(package_runtime.is_modular_jar(regular))
        self.assertTrue(package_runtime.is_modular_jar(multi_release))
        self.assertFalse(package_runtime.is_modular_jar(empty_javafx))

    def test_extracts_native_libraries_without_flattening_conflicts(self):
        first = self.jar("lwjgl-natives-test.jar", {"mac/libsample.dylib": b"one"})
        duplicate = self.jar("shaderc-natives-test.jar", {"other/libsample.dylib": b"one"})
        destination = self.root / "native"

        extracted = package_runtime.extract_natives([first, duplicate], destination)

        self.assertEqual([destination / "libsample.dylib"], extracted)
        conflicting = self.jar("other-natives-test.jar", {"libsample.dylib": b"two"})
        with self.assertRaisesRegex(RuntimeError, "Conflicting native library"):
            package_runtime.extract_natives([conflicting], destination)

    def test_wraps_png_in_a_windows_icon_container(self):
        png = (package_runtime.ROOT
               / "src/main/resources/com/shangin/fractal/app/icons/fractalui.png")
        icon = self.root / "icon.ico"

        package_runtime.create_windows_icon(png, icon)

        reserved, image_type, count = struct.unpack("<HHH", icon.read_bytes()[:6])
        self.assertEqual((0, 1, 1), (reserved, image_type, count))
        embedded = icon.read_bytes()[22:]
        self.assertEqual(b"\x89PNG\r\n\x1a\n", embedded[:8])
        self.assertEqual((256, 256), struct.unpack(">II", embedded[16:24]))

    def test_rejects_non_png_windows_icon_source(self):
        source = self.root / "icon.png"
        source.write_bytes(b"not a png")
        with self.assertRaisesRegex(RuntimeError, "not a PNG"):
            package_runtime.create_windows_icon(source, self.root / "icon.ico")

    def test_derives_numeric_package_version_from_maven_project(self):
        self.assertEqual("1.0", package_runtime.project_version())

    def test_uses_system_maven_when_the_wrapper_is_absent(self):
        with (mock.patch.object(package_runtime, "ROOT", self.root),
              mock.patch.object(package_runtime.shutil, "which", return_value="/tools/mvn")):
            self.assertEqual(Path("/tools/mvn"), package_runtime.maven_command())

    def test_stages_complete_pinned_license_materials(self):
        destination = self.root / "licenses"

        staged = package_runtime.stage_license_materials(destination)

        expected = {"THIRD-PARTY-LICENSES.txt", *package_runtime.LICENSE_SHA256}
        self.assertEqual(expected, {path.name for path in staged})
        self.assertEqual(expected, {path.name for path in destination.iterdir()})
        for name, digest in package_runtime.LICENSE_SHA256.items():
            self.assertEqual(digest, package_runtime.sha256(destination / name))

    def test_java_tool_option_preserves_cache_path_with_spaces(self):
        cache = self.root / "FractalUI cache"
        option = package_runtime.quote_java_tool_option(
            f"-Djavafx.cachedir={cache.as_posix()}")
        environment = dict(os.environ)
        environment["JAVA_TOOL_OPTIONS"] = option

        result = subprocess.run(
            [package_runtime.java_tool("java"), "-XshowSettings:properties", "-version"],
            capture_output=True, text=True, env=environment)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f"javafx.cachedir = {cache.as_posix()}", result.stderr)


if __name__ == "__main__":
    unittest.main()
