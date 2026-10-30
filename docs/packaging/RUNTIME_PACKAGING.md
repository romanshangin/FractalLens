# Runtime packaging

Automatic builds smoke-test without uploads. Reviewed manual dispatch may set
`upload_artifacts: true` after private-destination validation/privacy-checked
provenance/smoke text. PRs cannot enable uploads; exclude test logs/Surefire.
[CI controls](../development/CI.md).

JDK 25 `jlink`/`jpackage` produces self-contained macOS DMG (`FractalLens.app`)
and Windows EXE; destination needs no Java.

GPU runtime starts disabled; fully functional CPU default. Include platform
JavaFX/LWJGL natives, macOS-built AppKit menu bridge, icon, Apache License 2.0,
complete pinned third-party licenses/notices, embedded JDK legal text/provenance.
`packaging/THIRD-PARTY-LICENSES.txt` records each text's source tag/native revision.

## Startup and macOS deployment floor

Packages and Maven `javafx:run`/native development share plain
`com.shangin.fractal.app.FractalLauncher`: set macOS `prism.order=mtl,es2,sw` only
if absent, forward args to `Application.launch(FractalFxApplication.class, args)`.
Explicit/empty JVM values win; Windows/Linux defaults stay. Initialization can
fall back to ES2/SW, native crashes cannot. VirtualBuddy needs `-Dprism.order=sw`.
Owner accepted latency 2026-10-29, historical verdicts unchanged. Standard/clean
DMG, guest/minimum-OS acceptance stays open in
[P1.7 checklist](../platform/METAL_MIGRATION_CHECKLIST.md); copied-DMG host check below.

Inspection: uncommitted bootstrap based on `2139aa063e22c057b1cd0a7f94433c7fd31de1a7`,
macOS 27.2 ARM64, Microsoft OpenJDK 25 (update 4, vendor patch 1), `pom.xml` JavaFX.
Observed metadata/binaries only, no new OS support or revised historical P0.2 evidence.

| Component inspected | Deployment floor or metadata |
| --- | --- |
| JDK `java`, `libjli`, `libjvm` | Mach-O `minos 11.0` |
| All native libraries in the ARM64 JavaFX graphics JAR, including `libprism_mtl` | Mach-O `minos 11.0` |
| AppKit bridge, both ARM64 and x86_64 slices | Mach-O `minos 11.0`; build scripts target 11.0 |
| Development launcher | Build script targets 11.0; source plist has no `LSMinimumSystemVersion` |
| Generated jpackage launcher and all 35 loose Mach-O binaries in the app image (runtime and LWJGL libraries included) | Mach-O `minos 11.0` |
| Generated app image `Contents/Info.plist` | `LSMinimumSystemVersion=10.11` |

The generated plist understates the binary deployment floor: it does not prove
that the app can run on macOS 10.11. macOS 11.0 is only the observed binary
lower bound for this ARM64 build. A supported minimum for the whole delivery
also needs the selected JDK vendor's OS requirements and working JavaFX/Metal
device capabilities on that OS. Minimum-OS acceptance remains open; neither
macOS 11 nor Intel Mac execution was tested. Reinspect when the JDK, JavaFX,
architecture, native build settings or packaging tools change.

The current-source app image passed packaged-launcher smoke (JavaFX, AppKit
bridge, CPU fallback and completed CPU render) and displayed a completed
restored Mandelbrot scene after a normal launch on this host. This was an
`--app-image-only` development-host check, not an installed-DMG, clean-machine,
Dock-tooltip or Metal-migration acceptance check.

On 2026-10-29, the Metal-first activation sources based on `ba05a0c` were built
with Microsoft OpenJDK 25 (update 4, vendor patch 1) on macOS 27.2 ARM64 into a
version 1.0.0 DMG.
The DMG was mounted read-only and the app copied to a temporary installation
directory, verifying all 132 regular-file hashes before detaching the image.
The copied app initialized Metal and passed JavaFX, AppKit and CPU smoke with
no development JDK on PATH and `JAVA_HOME` absent. Normal launch displayed the
completed restored frame and native badge; AppKit context-menu/Escape and
successful shutdown were checked. This does not establish a clean-machine or
standard Applications installation, installed-app Dock tooltip, Gatekeeper or
notarization acceptance. See the
[specialized acceptance record](../platform/METAL_MIGRATION_CHECKLIST.md#local-specialized-acceptance-2026-10-29).

Inspection of that package again found a binary floor of macOS 11.0 in all
35 loose Mach-O files and all eight JavaFX graphics native libraries, with
`LSMinimumSystemVersion=10.11` still in the generated plist. These historical
values predate the metadata correction below. Minimum-OS execution remains
roadmap follow-up; no supported minimum is declared from
these binary values.

### Deployment metadata correction (2026-10-29)

The packaging script sets `LSMinimumSystemVersion=11.0` in the application
image before jpackage signs it and creates the DMG. It reads the selected
JDK's `Info-lite.plist.template` from its `lib/modules` runtime image using
that same JDK's `bin/jimage extract`, changes only that key, and supplies the
result as `Info.plist` through jpackage's documented
[`--resource-dir` override](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html).
This preserves the JDK's launcher, bundle, version and file-association
placeholders instead of maintaining a separate copied plist. Only the single
resource is extracted into a temporary directory, which is removed afterwards.
The build does not require `jmods`: [Temurin 25 omits that directory by default](https://adoptium.net/news/2025/09/eclipse-temurin-25-available)
with JEP 493 enabled. Missing or multiple deployment keys, an unrecognized version, or a JDK template requiring
an OS newer than 11.0 fail preparation rather than silently lowering its
requirement. Packaged-image validation rejects a missing or different value
before launching the smoke test. Windows packaging does not use this override.

`MACOS_DEPLOYMENT_FLOOR` is a packaging binary floor, not a promise of support
on macOS 11.0. The inspected JDK, JavaFX graphics/Metal, jpackage launcher,
LWJGL libraries and AppKit bridge have an 11.0 binary floor; native build
scripts also target 11.0. The development launcher's source plist is unchanged
and makes no minimum-version claim. Reassess this setting when changing JDK,
JavaFX, native libraries, architecture or native build targets.

The [Microsoft JDK support policy](https://learn.microsoft.com/en-us/java/openjdk/support)
covers maintained operating systems receiving security fixes; it does not
establish macOS 11 support for this application. CI uses Temurin, whose
[supported-platform matrix](https://adoptium.net/supported-platforms/) must be
checked separately from this local Microsoft JDK build. JavaFX's
[release notes](https://docs.oracle.com/en/java/java-components/javafx/26/release-notes/)
identify macOS architectures and JDK requirements, not a tested minimum OS for
this bundle. Binary load commands alone also do not establish Metal device
capability or runtime correctness. Execution on the eventual supported minimum
OS, Intel Mac, clean-machine installation and Gatekeeper acceptance remain open.

Initial developer self-validation on Apple M3 Pro, macOS 27.2 ARM64,
Microsoft OpenJDK 25 (update 4, vendor patch 1) and JavaFX from `pom.xml`:

- The initial 14 packaging contract tests and all three native launcher tests passed.
- `./mvnw test` passed: 640 tests, zero failures/errors, 135 opt-in skips.
  Skipped graphical/GPU checks are not accepted by this suite.
- A fresh `package_runtime.py --version 1.0.0` build produced the DMG and
  passed packaged JavaFX/AppKit/CPU smoke in the active desktop session.
- Both the app image and the app inside the read-only mounted DMG passed
  `codesign --verify --deep --strict`. The mounted app retained the expected
  bundle identifier, executable and version, declared `11.0`, and passed a
  separate packaged JavaFX/AppKit/CPU smoke.
- Reinspection found `minos 11.0` in all 35 loose Mach-O binaries and all eight
  JavaFX graphics natives from the staged dependency JAR. The metadata now
  matches this inspected binary floor.

These checks do not include a normal interactive launch/Dock-tooltip check,
installation into Applications, a clean machine, macOS 11, another JDK vendor,
Intel Mac or Windows execution. Raw build, test and inspection output stays
private. P1.7's supported-minimum and platform gates remain open.

### JDK runtime-image extraction follow-up (2026-10-29)

Review identified that the initial template reader required
`jmods/jdk.jpackage.jmod`, absent from Temurin 25 and therefore incompatible
with the macOS Runtime Artifacts workflow. The reader now uses the selected
JDK's `jimage` and `lib/modules` as described above. The contract fixture has
no `jmods`, checks selection of the same JDK's tool and runtime image with a
path containing spaces, preserves the remaining template, and checks temporary
extraction cleanup. A separate regression propagates extraction failure without
writing an override. The no-`jmods` regression fails with `FileNotFoundError`
against the previous reader and passes with runtime-image extraction.

Current developer self-validation used Eclipse Temurin 25.0.5+8, macOS 27.2
ARM64 and the dependencies from `pom.xml` on Apple M3 Pro. The official
Adoptium archive's SHA-256 was verified against its API metadata, and the
extracted JDK had `lib/modules` and no `jmods` directory.

- Direct template extraction using that JDK passed without mocked tools.
- All 15 packaging contract tests and all three native launcher tests passed
  with Temurin selected through `JAVA_HOME` and `PATH`.
- `./mvnw test` passed: 640 tests, zero failures/errors, 135 opt-in skips.
- A fresh `package_runtime.py --version 1.0.0` run built the app image and DMG,
  including successful JavaFX/AppKit/CPU smoke.
- The app image and the application in the read-only mounted DMG both declared
  `11.0` and passed `codesign --verify --deep --strict`; the mounted application
  also passed a separate JavaFX/AppKit/CPU smoke. All 35 loose Mach-O binaries
  in the Temurin-based app image again reported `minos 11.0`.

Packaging emitted `Warning: No JDK Modules found`; it was retained in the
private log, and the build and packaged-runtime checks above completed
successfully. The host's `hdiutil` also emitted an attach-command deprecation
warning; read-only mounting and subsequent `diskutil eject` succeeded.
The GitHub workflow itself was not dispatched. This local Temurin acceptance
does not establish Windows/Intel Mac execution, clean-machine installation,
interactive Dock identity, minimum-OS runtime support or vendor support on
macOS 11. The original Microsoft-JDK results above retain their original scope.

## Reproducible build command

Set `JAVA_HOME` to a full JDK 25 for the target platform and make Maven
available through the repository wrapper or `PATH`, then run:

```shell
python3 scripts/packaging/package_runtime.py --version 1.0.0
```

On Windows, use `python` if that is the installed Python command. WiX Toolset 3.14 is
required by `jpackage` to create the EXE installer. Package on the target operating
system; `jpackage` does not cross-package native installers.

Clean Maven package → minimal modular runtime/app image → launcher/JavaFX/AppKit
(macOS)/CPU fallback/complete small render smoke → installer. Use
`--app-image-only` during iteration without installer.

Outputs are under `target/runtime-package`:

- `artifacts/FractalLens-<version>.dmg` or the Windows EXE installer;
- an adjacent SHA-256 file;
- `artifact-provenance.json`, with revision, dirty-tree state, JDK, platform,
  module/native/license hashes, smoke result and final artifact hash;
- `smoke-report.properties`, the packaged-launcher result.

`.github/workflows/runtime-artifacts.yml` runs from clean hosted macOS/Windows
checkouts, retaining installer/checksum/provenance/smoke together for 30 days.
Hosted acceptance covers its architecture only; Intel/AMD Mac/additional Windows
hardware remains in `ROADMAP.md`.

## Clean-machine launch checklist

Use a machine without a development JDK or Maven installation.

1. Download the installer, checksum, provenance and smoke report from the same
   workflow artifact. Verify the SHA-256 checksum and confirm that provenance
   names the expected revision, version, target OS/architecture and successful
   smoke checks.
2. Install with the platform-native artifact. On macOS, mount the DMG and copy
   `FractalLens.app` to Applications. On Windows, run the EXE and choose the
   installation directory.
3. Launch FractalLens from Finder or the Windows Start menu. Confirm the window
   opens without an installed Java runtime, the FractalLens icon is visible, and
   the initial Mandelbrot frame reaches a complete state.
4. Pan, zoom, use Reset View, open the canvas context menu, and export a PNG.
   On macOS confirm the canvas menu uses AppKit; on Windows confirm the JavaFX
   fallback menu works.
5. Close and reopen the application. Confirm that no Java, JavaFX, LWJGL or
   AppKit-library error is shown and that normal CPU rendering still works.

Record run URL, artifact checksum/provenance, target machine, install/checklist
results. Build-host smoke is not clean-machine acceptance.

## P0.2 acceptance record

Original private-repository evidence: Actions/artifacts/PR revisions are not
published or new-destination CI proof. Original checksums/revisions remain provenance:
[publication](../development/PUBLICATION.md).

Owner closes P0.2 with the macOS VM exception below, without passing failed
default launch or supporting that environment.

- Historical Runtime Artifacts run `35557346815` (private archive)
  passed its hosted macOS ARM64 and Windows X64 packaging, packaged-launcher
  smoke, and artifact-upload jobs. The PR head was
  `cdeff9d4181f69031b4a8e14236381659d1d38ad`; artifact provenance records
  the PR merge revision `3331e33c7babf3635cd4f1ade1e75cff0a8595fc`,
  version `1.0.3`, and `working_tree_dirty=false` on both platforms.
- The macOS artifact `FractalLens-1.0.3.dmg` had SHA-256
  `71d2dfb9a6d6c3513aabfbb51077e0b3b40641e44a425f765eee7ac7cf02a0e7`. On a VirtualBuddy
  macOS Sonoma 14.8.9 (23J631) ARM64 guest without JDK or Maven, the downloaded artifact
  checksum was independently verified and provenance checks passed in the guest. The app
  installed, and the unsigned-app Gatekeeper exception was granted. The default Finder
  launch showed the loading screen and then crashed with `NSInvalidArgumentException`:
  `AppleParavirtDevice newArgumentEncoderWithLayout:` in `libprism_mtl.dylib`. An
  explicit ES2 launch could not initialize `MacGLFactory`. With
  `JAVA_TOOL_OPTIONS="-Dprism.order=sw -Dprism.verbose=true"`, the installed app
  initialized `SWPipeline`, displayed the completed Mandelbrot frame, and passed Dock
  tooltip, pan, zoom, Reset View, AppKit context menu/Escape, PNG export, and app-icon
  checks. The default Finder launch failed; a physical clean Apple Silicon Mac was
  unavailable, so physical macOS launch behavior is unverified. The software-pipeline
  result is a diagnostic control, not a general macOS graphics default or a pass for the
  default-launch step.
- The Windows artifact `FractalLens-1.0.3.exe` had SHA-256
  `b18c687b666452414b9574021ac1c6d539471671056d254383db9da6f08a4826`.
  On a Windows 11 Home 10.0.26200 x64 laptop, Java and Maven were removed
  before testing; a fresh PowerShell session found no `java.exe`, `javac.exe`,
  or `mvn.cmd`, and `JAVA_HOME` was empty. The SHA-256 matched the provenance
  artifact hash. Provenance recorded `machine=AMD64`, `cpu_default=true`, and
  successful JavaFX and CPU-fallback/render smoke checks. The owner reported
  successful installation, launch from the Start menu, initial render, pan,
  zoom, Reset View, JavaFX canvas context menu, PNG export, and close/reopen.
  This was a machine with development tools removed, not a newly installed OS.

Virtual Metal crash is unresolved. Never claim Sonoma/VirtualBuddy default launch
passed, infer physical behavior from VM, or call unsigned CI artifacts release
candidates. Signing/notarization remain separate.

## Signing and release boundary

CI artifacts are unsigned; build/smoke needs no release credentials. Separate
macOS Developer ID signing/notarization/stapling and Windows Authenticode must
use validated artifacts. Unsigned Gatekeeper/SmartScreen warnings are possible;
these are not public release candidates.

FractalLens source code, project documentation, screenshots, and original project
images are licensed under the [Apache License 2.0](../../LICENSE), unless otherwise
noted. Bundled third-party components and assets retain their respective
licenses, listed in `packaging/THIRD-PARTY-LICENSES.txt`.
