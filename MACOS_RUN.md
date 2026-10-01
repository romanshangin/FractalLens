# Running FractalLens on macOS

Run `mvn compile javafx:run` (or `mvn javafx:run` after compilation).
The automatically activated `macos-app-name` profile launches through
`scripts/macos-java`. Dock sees a `FractalLens.app` bundle with its own name,
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

Run `python3 scripts/test_macos_launcher.py` for opt-in macOS checks of the
SDK identity, bundle signature, concurrent creation, cache reuse, argument
quoting, `@argfiles`, `JDK_JAVA_OPTIONS` and exit status. Set `JAVA_HOME` to
check a particular JDK. The native shim uses OpenJDK's exported JLI launcher
ABI, so these checks should be repeated when changing the JDK major version.

This bundle is for Maven development runs and depends on the installed JDK.
It is not a standalone distribution or a Finder launcher. In an IDE, use the
same Maven goal to obtain the Dock name; a direct Java Application run bypasses
the bundle. Windows and Linux keep the normal Java launcher.

For the self-contained Finder application and DMG installer, use the pipeline
documented in `RUNTIME_PACKAGING.md`. That artifact includes its own runtime and
does not use this development launcher.

For a manual check, launch the application and hover over its Dock icon:
the tooltip must say **FractalLens**. Checking only the running process's
`localizedName` is insufficient: it can be FractalLens while Dock still says java.
