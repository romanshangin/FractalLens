# Running FractalLens on macOS

Run `./mvnw compile javafx:run` (or `./mvnw javafx:run` after compilation).
The automatically activated `macos-app-name` profile launches through
`scripts/macos/macos-java`. Dock sees a `FractalLens.app` bundle with its own name,
identifier and icon, rather than the shared `java` executable.

Local SDK builds/signs a development bundle in `target/macos-launcher/`, linked
to Maven JDK runtime libraries without modifying the JDK. Requires Xcode/Command
Line Tools. Reuse unchanged bundles; `mvn clean` removes them, next launch rebuilds.
Forward JavaFX-plugin JVM arguments, module paths and app arguments unchanged.

Default JavaFX decoration delegates title bar/close/minimize/fullscreen to AppKit.
For current Tahoe appearance build with SDK 26+; older SDKs get compatibility
controls, including smaller buttons. No JavaFX imitations/custom buttons.

`python3 scripts/macos/test_macos_launcher.py` opt-in checks SDK identity,
signature, concurrent creation/cache reuse, quoting, `@argfiles`, `JDK_JAVA_OPTIONS`
and exit status. Select JDK with `JAVA_HOME`; repeat for major JDK changes because
the shim uses OpenJDK's exported JLI launcher ABI.

Development bundle needs the installed JDK; not a standalone/Finder app. IDEs
must use the same Maven goal for Dock identity; direct Java launch bypasses it.
Windows/Linux use the normal launcher.

Self-contained Finder/DMG distribution has its own runtime and no development
launcher: `docs/packaging/RUNTIME_PACKAGING.md`.

Manual identity check: launch, hover Dock icon, require **FractalLens** tooltip.
`localizedName` alone can say FractalLens while Dock still says java.

## JavaFX startup and Prism selection

Maven/packages enter plain `com.shangin.fractal.app.FractalLauncher`, not JavaFX
`Application`. Its pre-toolkit `main` calls
`Application.launch(FractalFxApplication.class, args)`. Native development launch
forwards module/main-class unchanged; no separate native pipeline policy.

macOS sets `prism.order=mtl,es2,sw` before JavaFX only if absent; explicit values,
including empty, win. Windows/Linux defaults are unchanged. Metal addresses stale
Space-switch presentation; initialization falls back to ES2 then SW. Native
post-initialization crashes/hangs cannot recover. VirtualBuddy's known Metal crash
still needs `-Dprism.order=sw`; ES2 initialization also failed there.

Owner accepted latency costs on 2026-10-29; historical performance stays `fail`.
[P1.7](../../ROADMAP.md#p17-evaluate-metal-as-the-default-macos-prism-pipeline)
remains open for final-candidate Spaces/packaging/platform acceptance:
[checklist](METAL_MIGRATION_CHECKLIST.md).

For an explicit diagnostic selection, supply JVM options before launch, for example:

```shell
JAVA_TOOL_OPTIONS="-Dprism.order=mtl -Dprism.verbose=true" ./mvnw compile javafx:run
JAVA_TOOL_OPTIONS="-Dprism.order=es2 -Dprism.verbose=true" ./mvnw compile javafx:run
JAVA_TOOL_OPTIONS="-Dprism.order=sw -Dprism.verbose=true" ./mvnw compile javafx:run
```

Commands replace `JAVA_TOOL_OPTIONS`; preserve other required options. Pipeline
overrides forward unchanged. Direct-JavaFX tests/probes bypass `FractalLauncher`
and must select their pipeline explicitly.

Compatibility `com.shangin.fractal.app.FractalApplication` is plain Java,
delegating to `FractalLauncher` before toolkit startup. `FractalFxApplication`
owns lifecycle, not `main`. Unchanged `palette-benchmark`, `interaction-latency`,
`gpu-render-benchmark` main names now bootstrap via `FractalLauncher.launch` and
public lifecycle adapters. They and cold-startup measurement share app defaults;
user pipeline values win. Select `es2` explicitly for historical ES2 comparisons.
Non-graphical benchmarks never initialize Prism.
