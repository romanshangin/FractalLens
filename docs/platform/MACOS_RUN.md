# Running FractalLens on macOS

Run `./mvnw compile javafx:run` (or `./mvnw javafx:run` after compilation).
The automatically activated `macos-app-name` profile launches through
`scripts/macos/macos-java`. Dock sees a `FractalLens.app` bundle with its own name,
identifier and icon, rather than the shared `java` executable.

The launcher creates a small, locally signed development bundle under
`target/macos-launcher/`. It builds a small native launcher with the local
macOS SDK and links to the Maven JDK's runtime libraries; it does not modify
the installed JDK. This requires Xcode or its Command Line Tools.
Unchanged bundles are reused. `mvn clean` removes them, and the next launch
recreates them. JVM arguments, module paths and application arguments from
the JavaFX Maven plugin are forwarded unchanged.

The window title bar and close/minimize/full-screen buttons are provided by
AppKit through JavaFX's default decorated stage. On macOS Tahoe, build with
SDK 26 or newer to obtain the current native appearance. An executable built
with an older SDK gets the compatibility design, including smaller buttons.
No JavaFX imitation of the title bar or custom window buttons is used.

Run `python3 scripts/macos/test_macos_launcher.py` for opt-in macOS checks of the
SDK identity, bundle signature, concurrent creation, cache reuse, argument
quoting, `@argfiles`, `JDK_JAVA_OPTIONS` and exit status. Set `JAVA_HOME` to
check a particular JDK. The native shim uses OpenJDK's exported JLI launcher
ABI, so these checks should be repeated when changing the JDK major version.

This bundle is for Maven development runs and depends on the installed JDK.
It is not a standalone distribution or a Finder launcher. In an IDE, use the
same Maven goal to obtain the Dock name; a direct Java Application run bypasses
the bundle. Windows and Linux keep the normal Java launcher.

For the self-contained Finder application and DMG installer, use the pipeline
documented in `docs/packaging/RUNTIME_PACKAGING.md`. That artifact includes its own runtime and
does not use this development launcher.

For a manual check, launch the application and hover over its Dock icon:
the tooltip must say **FractalLens**. Checking only the running process's
`localizedName` is insufficient: it can be FractalLens while Dock still says java.

## JavaFX startup and Prism selection

Maven and the packaged application enter through
`com.shangin.fractal.app.FractalLauncher`. This class does not extend JavaFX
`Application`; its `main` runs before the toolkit and calls
`Application.launch(FractalFxApplication.class, args)`. The native development
launcher forwards Maven's module/main-class arguments unchanged, so it uses
the same entry point without a separate native pipeline setting.

On macOS, the bootstrap sets `prism.order=mtl,es2,sw` before starting
JavaFX, only when the property is absent. Any explicit value, including an
empty value, is preserved. Windows and Linux keep their JavaFX defaults.
Metal is preferred to address stale presentation after a Space switch. If it
cannot initialize, Prism tries ES2 and then software rendering. This chain
does not recover from a native crash or hang after pipeline initialization.
The known VirtualBuddy Metal crash therefore still requires an explicit
`-Dprism.order=sw` workaround; ES2 also failed to initialize in that guest.

The owner accepted the measured latency trade-off on 2026-10-29; the historical
performance gate remains `fail`. This activation candidate does not close
[P1.7](../../ROADMAP.md#p17-evaluate-metal-as-the-default-macos-prism-pipeline):
final-candidate Spaces, packaging and platform acceptance remain tracked by the
[Metal migration checklist](METAL_MIGRATION_CHECKLIST.md).

For an explicit diagnostic selection, supply JVM options before launch, for example:

```shell
JAVA_TOOL_OPTIONS="-Dprism.order=mtl -Dprism.verbose=true" ./mvnw compile javafx:run
JAVA_TOOL_OPTIONS="-Dprism.order=es2 -Dprism.verbose=true" ./mvnw compile javafx:run
JAVA_TOOL_OPTIONS="-Dprism.order=sw -Dprism.verbose=true" ./mvnw compile javafx:run
```

These commands replace `JAVA_TOOL_OPTIONS` for that command; preserve any other
required options when using them. User-supplied pipeline options are forwarded
unchanged. Tests and specialized probes that start JavaFX directly bypass
`FractalLauncher` and must select the intended pipeline explicitly.

The original `com.shangin.fractal.app.FractalApplication` main-class name is a
plain-Java compatibility entry point delegating to `FractalLauncher`; direct
Java launches now apply the same policy before starting the toolkit. The
JavaFX lifecycle belongs to `FractalFxApplication`, which is not a main class.
The `palette-benchmark`, `interaction-latency` and `gpu-render-benchmark`
Maven profiles retain their main-class names, but those classes are now plain
bootstraps calling `FractalLauncher.launch` with public JavaFX lifecycle
adapters. They therefore use the same default as the application. Cold-startup
measurement follows that bootstrap as well. User-supplied pipeline values still
win; comparisons with historical ES2 measurements must select `es2` explicitly.
Non-graphical benchmarks do not initialize Prism.
