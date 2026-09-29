# Runtime packaging

FractalUI produces platform-native, self-contained runtime artifacts with JDK
25 `jlink` and `jpackage`. The macOS output is a DMG containing `FractalUI.app`;
the Windows output is an EXE installer. Neither artifact requires Java to be
installed on the destination machine.

The application starts with the optional GPU runtime disabled. The CPU renderer
is the default and remains fully functional. Platform JavaFX modules and LWJGL
native libraries are included. The macOS artifact also compiles and includes
the AppKit canvas-menu bridge. The application icon, FractalUI's Apache License
2.0 text, complete pinned third-party license and notice texts, embedded JDK
legal notices and build provenance travel with the runtime.
`packaging/THIRD-PARTY-LICENSES.txt` records the source tag or embedded native
revision for every copied third-party text.

## Reproducible build command

Set `JAVA_HOME` to a full JDK 25 for the target platform and make Maven
available through the repository wrapper or `PATH`, then run:

```shell
python3 scripts/package_runtime.py --version 1.0.0
```

On Windows, use `python` if that is the installed Python command. WiX Toolset
3.14 is required by `jpackage` to create the EXE installer. Packaging must run
on the target operating system; `jpackage` does not cross-package native
installers.

The command performs a clean Maven package, constructs a minimal modular runtime,
builds an app image, starts its launcher, initializes JavaFX, loads the AppKit
bridge on macOS, verifies CPU fallback, completes a small CPU render, and only
then creates the installer. Use `--app-image-only` while iterating when an
installer is not needed.

Outputs are under `target/runtime-package`:

- `artifacts/FractalUI-<version>.dmg` or the Windows EXE installer;
- an adjacent SHA-256 file;
- `artifact-provenance.json`, with revision, dirty-tree state, JDK, platform,
  module/native/license hashes, smoke result and final artifact hash;
- `smoke-report.properties`, the packaged-launcher result.

`.github/workflows/runtime-artifacts.yml` runs the same command from a clean
checkout on hosted macOS and Windows runners. It retains the installer,
checksum, provenance and smoke report together for 30 days. A hosted runner
proves only its own architecture. Intel/AMD Mac and additional Windows hardware
coverage remains tracked separately in `ROADMAP.md`.

## Clean-machine launch checklist

Use a machine without a development JDK or Maven installation.

1. Download the installer, checksum, provenance and smoke report from the same
   workflow artifact. Verify the SHA-256 checksum and confirm that provenance
   names the expected revision, version, target OS/architecture and successful
   smoke checks.
2. Install with the platform-native artifact. On macOS, mount the DMG and copy
   `FractalUI.app` to Applications. On Windows, run the EXE and choose the
   installation directory.
3. Launch FractalUI from Finder or the Windows Start menu. Confirm the window
   opens without an installed Java runtime, the FractalUI icon is visible, and
   the initial Mandelbrot frame reaches a complete state.
4. Pan, zoom, use Reset View, open the canvas context menu, and export a PNG.
   On macOS confirm the canvas menu uses AppKit; on Windows confirm the JavaFX
   fallback menu works.
5. Close and reopen the application. Confirm that no Java, JavaFX, LWJGL or
   AppKit-library error is shown and that normal CPU rendering still works.

Record the workflow run URL, artifact checksum, provenance file, target machine,
installation result and checklist result. Do not describe a build-machine smoke
test as a completed clean-machine check.

## P0.2 acceptance record

The owner accepted P0.2 with the macOS virtual-machine exception below. This
decision closes the roadmap packaging item; it does not turn the failed default
macOS launch into a passing check or establish support for that environment.

- [Runtime Artifacts run 35557346815](https://github.com/romzesthefirst/fractal-ui/actions/runs/35557346815)
  passed its hosted macOS ARM64 and Windows X64 packaging, packaged-launcher
  smoke, and artifact-upload jobs. The PR head was
  `cdeff9d4181f69031b4a8e14236381659d1d38ad`; artifact provenance records
  the PR merge revision `3331e33c7babf3635cd4f1ade1e75cff0a8595fc`,
  version `1.0.3`, and `working_tree_dirty=false` on both platforms.
- The macOS artifact `FractalUI-1.0.3.dmg` had SHA-256
  `71d2dfb9a6d6c3513aabfbb51077e0b3b40641e44a425f765eee7ac7cf02a0e7`.
  On a VirtualBuddy macOS Sonoma 14.8.9 (23J631) ARM64 guest without JDK or
  Maven, the downloaded artifact checksum was independently verified and
  provenance checks passed in the guest. The app installed, and the unsigned-app
  Gatekeeper exception was granted. The default Finder launch
  showed the loading screen and then crashed with `NSInvalidArgumentException`:
  `AppleParavirtDevice newArgumentEncoderWithLayout:` in `libprism_mtl.dylib`.
  An explicit ES2 launch could not initialize `MacGLFactory`. With
  `JAVA_TOOL_OPTIONS="-Dprism.order=sw -Dprism.verbose=true"`, the installed app
  initialized `SWPipeline`, displayed the completed Mandelbrot frame, and
  passed Dock tooltip, pan, zoom, Reset View, AppKit context menu/Escape, PNG
  export, and app-icon checks. The default Finder launch failed; a physical
  clean Apple Silicon Mac was unavailable, so physical macOS launch behavior
  remains unverified. The software-pipeline result is a diagnostic control,
  not a general macOS graphics default or a pass for the default-launch step.
- The Windows artifact `FractalUI-1.0.3.exe` had SHA-256
  `b18c687b666452414b9574021ac1c6d539471671056d254383db9da6f08a4826`.
  On a Windows 11 Home 10.0.26200 x64 laptop, Java and Maven were removed
  before testing; a fresh PowerShell session found no `java.exe`, `javac.exe`,
  or `mvn.cmd`, and `JAVA_HOME` was empty. The SHA-256 matched the provenance
  artifact hash. Provenance recorded `machine=AMD64`, `cpu_default=true`, and
  successful JavaFX and CPU-fallback/render smoke checks. The owner reported
  successful installation, launch from the Start menu, initial render, pan,
  zoom, Reset View, JavaFX canvas context menu, PNG export, and close/reopen.
  This was a machine with development tools removed, not a newly installed OS.

The macOS virtual Metal crash remains an unresolved compatibility limitation.
Do not describe the Sonoma/VirtualBuddy default launch as validated, infer
physical Mac behavior from the VM, or treat these unsigned CI artifacts as
public release candidates. Signing and notarization remain separate release
work as described below.

## Signing and release boundary

CI artifacts are intentionally unsigned. Building and smoke testing are
independent of release credentials. macOS Developer ID signing, notarization and
stapling, and Windows Authenticode signing are separate release steps and must
operate on the already validated artifacts. Unsigned builds can trigger
Gatekeeper or SmartScreen warnings and are not public release candidates.

FractalUI source code, project documentation, screenshots, and original project
images are licensed under the [Apache License 2.0](LICENSE), unless otherwise
noted. Bundled third-party components and assets retain their respective
licenses, listed in `packaging/THIRD-PARTY-LICENSES.txt`.
