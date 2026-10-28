# macOS Spaces presentation validation

This is a correctness check for the ES2/Metal comparison, separate from the
[timing campaigns](PRISM_PIPELINE_BENCHMARK.md). The symptom is a completed
render that remains invisible after a Space switch or in Mission Control until
another pan causes a repaint. A software completion event alone is not a pass.

## Driver and scope

[`SpaceSwitchProbe`](../src/test/java/com/shangin/fractal/ui/SpaceSwitchProbe.java)
opens the production `MainView`, menus, controller, surface and AA path. It uses
an isolated session file and the direct-double CPU backend for the standard
Mandelbrot overview. Do not navigate into deep zoom in this driver. It neither
captures the scene graph nor adds animated markers or requests repaints.

F8 focuses the canvas, arms a calculation gate and sends a controlled 120-point
drag through the installed production mouse handlers. Successive trials drag
right, left, down and up. F7 arms the same gate without generating input, for
real pointer or arrow-key navigation. Calculation waits on a worker thread until three seconds after the
window loses focus. The gate also applies to replacement requests within that
trial. Cancellation interrupts the wait, and close releases it and closes the
backend. Missing focus loss times out the render after 30 seconds; this is an
invalid trial, not evidence of the presentation defect. The gate changes timing
deliberately and must never be used as a performance measurement.

The log records `waiting`, `focus=false`, `release`, `frame_promoted`, and
`complete`, including the viewport and physical render dimensions. Focus loss
does not prove a Space switch: the operator must independently observe that
another Space or Mission Control is displayed. A valid offscreen trial needs
the new request to reach `complete` while `focused=false`, before returning.

F9 records a read-only model checkpoint. Use it only after recording the unaided
visual verdict. Keyboard input, activating the app, opening a menu, snapshots,
export and pointer movement over the canvas must not precede that verdict.

## Build and launch

Use the same compiled sources and dependencies for both pipelines. In the
repository root, build with the checked-in Maven Wrapper:

```shell
./mvnw -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/space-probe-classpath.txt
```

Run the following in an active graphical macOS session. `JAVA_HOME` must be the
JDK used for compilation; use a fresh private `OUTPUT` directory. Raw logs and
desktop captures remain private. Set `PIPELINE=es2`, then repeat with `mtl`,
using different session files and caches. Do not rebuild between those runs.

```shell
PIPELINE=es2
OUTPUT="${TMPDIR}/fractallens-spaces-new-run"
mkdir -p "$OUTPUT"
scripts/macos/macos-java "--fractal-java-home=$JAVA_HOME" \
  --module-path "target/classes:$(cat target/space-probe-classpath.txt)" \
  --patch-module com.shangin.fractal=target/test-classes \
  --enable-native-access=javafx.graphics,org.lwjgl,com.shangin.fractal \
  --add-exports=javafx.graphics/com.sun.glass.ui=com.shangin.fractal \
  --add-opens=javafx.graphics/com.sun.glass.ui.mac=com.shangin.fractal \
  --add-exports=javafx.graphics/com.sun.javafx.stage=com.shangin.fractal \
  --add-exports=javafx.graphics/com.sun.javafx.tk=com.shangin.fractal \
  -Dfractal.gpu.enabled=false -Dfractal.gpu.mandelbrot.enabled=false \
  -Dprism.verbose=true "-Dprism.order=$PIPELINE" \
  "-Djavafx.cachedir=$OUTPUT/cache-$PIPELINE" \
  -m com.shangin.fractal/com.shangin.fractal.ui.SpaceSwitchProbe \
  "$OUTPUT/session-$PIPELINE.json" > "$OUTPUT/$PIPELINE.log" 2>&1
```

Check that each log initializes exactly the requested `ES2Pipeline` or
`MTLPipeline`. Remove inherited JVM environment overrides for the campaign;
do not infer the active pipeline from the requested property alone.

## Trial matrix and verdicts

Start at a completed default Mandelbrot overview. For each pipeline, exercise
pan left, right, up and down in each of these two sequences:

1. Press F8 for a controlled drag (or F7 followed by an actual pan), immediately
   switch to an adjacent Space, wait for the
   logged offscreen completion, then return using the system Space shortcut.
   Inspect the newly exposed region without further application input.
2. Start another controlled or manual pan, switch away and wait for offscreen completion. Enter Mission
   Control and inspect the original Space's thumbnail without selecting it.
   Record this verdict separately, then return and record the window verdict.

Use actual drag gestures as well as the installed arrow-key navigation. Do not
count a completion before focus loss, a failed Space switch, an unreadable
thumbnail, or an app-window capture that does not show the compositor's visible
output as a successful trial. If thumbnails are collapsed, expose them using
Mission Control controls, without activating the original Space.

Also repeat natural, ungated pan/switch attempts in a fresh process without
pressing F7 or F8. These test real gesture timing; label a trial inconclusive when
completion occurred before leaving. Keep controlled and natural counts separate.
Mode, gesture, direction, logical window size, output scale, pipeline, completion
ordering, visible outcome and recovery action belong in the trial record.

A visible failure is stale, missing, banded or mixed-frame fractal content after
confirmed completion. Only after recording it, try a small pan and record
whether it makes the completed content visible. An accessibility status badge
or buffer fingerprint does not prove that the fractal is displayed.

Report pipeline-specific failures over valid trials and all invalid/unobserved
trials. No failures in a finite run is bounded evidence, not proof of universal
correctness. If ES2 never reproduces the symptom, the run does not establish a
regression comparison even if Metal also shows no failures. Completing this
check does not waive the remaining FX, appearance, fallback, packaging and
platform checks in the pipeline protocol.

## Execution record

2026-10-28 preparation on Apple M3 Pro, macOS 27.2, JDK 26.0.1 and JavaFX
26.0.2, branch `chore/prism-pipeline-benchmark` based on `bcbfca4` with the new
driver uncommitted:

- Two early ES2 driver launches initialized `ES2Pipeline` and displayed a
  completed default Mandelbrot window at 1200x760 logical points, scale 2.
- The initial keyboard attempts did not produce a new render, even after the
  canvas focus was confirmed. Native pointer automation then returned
  `noWindowsAvailable`. These attempts are invalid trials, not presentation
  failures or successful acceptance checks.
- Desktop launch access subsequently became unavailable. The final driver adds
  the controlled F8 drag so that the next run does not depend on that pointer
  automation. It still requires an independently observed system Space switch.
- Valid offscreen-completion trials: ES2 **0**, Metal **0**. Return-to-Space and
  Mission Control thumbnail acceptance are **pending**. No comparison of
  failure rates or conclusion that Metal fixes the defect is established by
  these preparation runs.

The driver gate has portable tests covering held calculation until scheduled
release, completed pixel contents, cancellation without publication and
idempotent close while calculation is held. They do not replace the visible
desktop matrix. Raw launch logs remain private; the final controlled-drag
driver has not yet completed a desktop acceptance trial.

Developer checks on the final driver passed: all three `SpaceSwitchProbeTest`
tests; the full portable suite with an isolated JavaFX cache (481 executed,
130 opt-in skips, no failures/errors); all 14 publication-script tests;
`check_public_data.py`; secret scans of history and the candidate tree; and
`git diff --check`. The skipped FX/native tests are not desktop acceptance.
