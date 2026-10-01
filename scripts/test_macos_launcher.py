"""Opt-in native launcher checks: python3 scripts/test_macos_launcher.py."""
import concurrent.futures
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import tempfile
import unittest


@unittest.skipUnless(sys.platform == "darwin", "macOS launcher")
class MacLauncherTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="FractalLens launcher test ")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        project = Path(__file__).resolve().parent.parent
        for relative in ("scripts/macos-java", "src/main/macos/FractalLauncher.c",
                         "src/main/macos/Info.plist",
                         "src/main/resources/com/shangin/fractal/app/icons/FractalLens.icns"):
            destination = cls.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(project / relative, destination)
        java_home = os.environ.get("JAVA_HOME") or subprocess.check_output(
            ["/usr/libexec/java_home"], text=True).strip()
        jdk_link = cls.root / "JDK with spaces"
        jdk_link.symlink_to(java_home)
        cls.command = [str(cls.root / "scripts/macos-java"),
                       f"--fractal-java-home={jdk_link}"]
        cls.environment = dict(os.environ)
        for name in ("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS"):
            cls.environment.pop(name, None)
        cls.source = cls.root / "LaunchProbe.java"
        cls.source.write_text('''class LaunchProbe {
            public static void main(String[] args) {
                System.out.println(System.getProperty("probe"));
                for (String arg : args) System.out.println(arg);
                System.exit(7);
            }
        }''')

    def launch(self, arguments, **environment):
        return subprocess.run(self.command + arguments, capture_output=True,
                              text=True, timeout=45,
                              env={**self.environment, **environment})

    def test_bundle_sdk_and_concurrent_creation(self):
        shutil.rmtree(self.root / "target", ignore_errors=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: self.launch(["-version"]), range(2)))
        for result in results:
            self.assertEqual(0, result.returncode, result.stderr)
        bundles = list((self.root / "target/macos-launcher").glob("*/FractalLens.app"))
        self.assertEqual(1, len(bundles))
        bundle = bundles[0]
        info = plistlib.loads((bundle / "Contents/Info.plist").read_bytes())
        self.assertEqual("FractalLens", info["CFBundleDisplayName"])
        subprocess.run(["codesign", "--verify", "--strict", str(bundle)], check=True)
        executable = bundle / "Contents/MacOS/FractalLens"
        binary = subprocess.check_output(["otool", "-l", str(executable)], text=True)
        sdk = subprocess.check_output(
            ["xcrun", "--sdk", "macosx", "--show-sdk-version"], text=True).strip()
        self.assertIn("sdk " + sdk, binary)
        timestamp = executable.stat().st_mtime_ns
        self.assertEqual(0, self.launch(["-version"]).returncode)
        self.assertEqual(timestamp, executable.stat().st_mtime_ns)

    def test_literal_arguments_and_exit_status(self):
        arguments = ['space value', 'quote " value', "literal $HOME `whoami`",
                     '--fractal-java-home=application-argument', 'кириллица', '@literal']
        result = self.launch(["-Dprobe=value with spaces", str(self.source)] + arguments)
        self.assertEqual(7, result.returncode, result.stderr)
        self.assertEqual(["value with spaces"] + arguments, result.stdout.splitlines())

    def test_argfile_and_environment_options(self):
        argfile = self.root / "java arguments.txt"
        argfile.write_text(f'-Dprobe="from argfile"\n"{self.source}"\n"app argument"\n')
        result = self.launch(["@" + str(argfile)])
        self.assertEqual(7, result.returncode, result.stderr)
        self.assertEqual(["from argfile", "app argument"], result.stdout.splitlines())
        result = self.launch([str(self.source)], JDK_JAVA_OPTIONS='-Dprobe="from env"')
        self.assertEqual(7, result.returncode, result.stderr)
        self.assertEqual(["from env"], result.stdout.splitlines())


if __name__ == "__main__":
    unittest.main()
