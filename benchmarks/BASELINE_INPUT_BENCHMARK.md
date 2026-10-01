# Roadmap 9.1 production input baseline

> Publication scope: use this protocol for fresh runs in a new output directory.
> Keep raw logs and diagnostic captures private; publish only reviewed summaries
> and necessary sanitized evidence. See [evidence provenance](../PUBLICATION.md).

[`BaselineInputBenchmark`](../src/test/java/com/shangin/fractal/render/BaselineInputBenchmark.java)
connects the versioned `9.1-v1` [fixture matrix](BASELINE_BENCHMARK.md) to the
installed `FractalView` scroll, zoom, mouse and resize handlers. It dispatches
JavaFX events into a visible scene and includes camera updates, grid snapping,
debounce, controller preparation, callbacks and base/AA publication. All
calculation uses the production CPU backend selection. It shares the baseline
process lock, dimensions, fixture selection and Fast/Refined selection.

The input origin is entry to the JavaFX handler, not a physical device. The
post-layout observations retain the publication's input/render identity and
measure a software scheduling boundary. Hardware/OS delivery, compositor
readback and physical scanout remain unmeasured. The older screen-marker
[interaction experiment](INTERACTION_LATENCY.md) is still available separately.

## Scenarios and actual requests

Single-step scenes use wheel, six wheel events, six pinch updates, six trackpad
updates and six drag updates. Updates are dispatched sequentially with a
requested 16 ms sleep between them; FX queue/handler time is additional, and
actual entry times are preserved in `events.csv`. Pinch start/finish and mouse
press/release use the installed handlers. Wheel deltas are `(0,40)`, pinch
factors `1.03`, trackpad deltas `(8,3)` logical pixels, and drag ends at `(48,18)`.
The pointer starts at the logical pixel-grid center `(size-1)/2`.

Multi-step fixtures run as navigation sequences: pan percentages and reverse
pan become drags, resize changes the view's width/height properties, and
scale transitions become centered pinch events. The next target is translated
from the current *actual* viewport into logical input deltas. Physical render
sizes are exact and checked, with integer logical dimensions at the current
output scale. An unmanaged view preserves large buffers even when part of the
view lies outside the visible window; publication does not mean every target
pixel was visible on the display.

`cancel-direct` and `cancel-deep` dispatch a second wheel event queued at the
first request's `render_submit` boundary. This tests production cancellation
and replacement by newer input. This trigger is deliberately distinct from the
headless matrix's first-backend-region cancellation. Base-worker drain/tail time is available with the opt-in
[scheduling diagnostics](BASELINE_SCHEDULING_BENCHMARK.md); default runs leave it unmeasured. The raw trace retains callbacks from earlier generations;
they cannot supply completion for the last input.

Every sequence starts with a new view/controller, an exact source viewport,
the fixture formula, palette/trap/histogram, iteration policy and AA pattern.
Test-only reflection is isolated in `BaselineInputAccess` for this untimed
setup and inspection. Navigation never assigns the camera or calls a render
method directly. Source rendering and conformance finish before recording.
Navigation retains the production base/AA caches within the sequence.

The UI policy is authoritative:

- Adaptive iterations use the camera's fitted home viewport, which can produce
  a different cap from the canonical headless fixture. Pan/resize preserve
  the current cap; zoom recomputes it.
- Direct UI frames always run AA, including seeds marked base-only in the
  component matrix. Deep AA is enabled after a deep AA seed has loaded. Its
  transient production toggle stays off when entering deep from a direct seed.
- Pinch factors pass through `double`; an intended reverse to `1e-9` can become
  `9.9999999999999984e-10`. This is not the canonical exact reverse-cache hit.
- A clamped gesture can leave the view unchanged. Such trials are recorded as
  `no_change`, with no invented render/completion timestamps. Home views are
  not silently zoomed to make a pan work.

`manifest.csv` contains the unchanged selected canonical fixtures.
`actual-manifest.csv` records the exact source and final job for every trial,
including decimal coordinates, physical dimensions, actual cap, formula,
accuracy, AA pattern/grid and enabled AA. Caps there are frozen as `fixed` for
same-grid comparison; this does not replace the UI's live policy. Cache fields
explicitly identify prepared sources and conditional retained pixels. Compare
CPU/FX scopes only with these actual jobs and the same retention contract;
canonical fixture names alone do not establish equivalent work.

## Timing and validation

`events.csv` is the generation-aware trace from `InteractionLatency`. Every
record has a unique enclosing trial, recording epoch, input and render identity.
It includes handler entries, preview, preparation, submission, first-ready and
first-callback milestones, base/AA publication, completion and post-layout
observations. Callback queue/work counters retain their original generation.
`input_resize` records each size/scale listener entry before resize preparation.

`samples.csv` summarizes first-input → preview and its post-layout observation;
last-input → render start, base publication, AA publication, full completion,
and completion post-layout; and last-input → first target publication and its
post-layout observation. First target includes base, AA or frame promotion.
`-1` means absent, never zero. In requested Refined mode, hidden base publication
is absent. Deep rendering uses the production Fast presentation regardless of
the requested mode. Preview is approximate coverage, not exact target data.
These overlapping scopes must not be added together.

Every seed is checked against fresh same-grid CPU samples and ARGB output before
it becomes a source for navigation. After each trial, the control independently builds the target using the same
active-source reuse selection and computes its missing samples on the CPU.
Every sample is compared bit for bit, including smooth/trap values and escape
flags. Retained deep samples keep their previously verified values: recalculating
them around a new reference orbit is a different floating-point path. The ARGB
control independently colors/refines that frame and shifts the *previously
verified* source pixels according to the reuse planner.
This preserves the UI's retained-AA policy: image-space candidate neighborhoods
at the old border can differ from a fresh all-empty AA pass. No output tolerance
or fingerprint-only acceptance is used. The next sequence step starts only after
its predecessor passes. `SUCCESS` is written only if every control passes.

`first_sequence` denotes the first sequence of a case in this JVM. `warmup` and
`sample` are later sequences. Every sequence has newly created services but a
fully prepared source, and control work runs between sequences. These labels
are not cold-source latency, process-cold measurements or uninstrumented timing
pairs. Use this runner for scope/conformance validation and separate decision
runs when applying the 30-pair/multiple-process performance gate.

The summarizer requires `SUCCESS`, every selected fixture/scenario/mode/run,
unique trials, source/target membership, consistent event identities, raw-event
agreement with reported timings and repeatable endpoints/fingerprints. It reports
sample medians, nearest-rank p95/max and the number of present observations;
missing timings are excluded, and wholly absent scopes stay `-1`. Asynchronous
JavaFX failures and an idle frame that disagrees with the camera abort the run.
Neither the
runner nor summarizer overwrites historical output.

## Reproduction

Run timing suites sequentially with a graphical session. Use the same JDK as
Maven and the component baselines.

```sh
mvn -q -DskipTests test-compile dependency:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile=target/baseline-classpath.txt

BASELINE_JAVA=/path/to/jdk/bin/java
"$BASELINE_JAVA" -Xmx4g \
  --module-path "target/classes:$(cat target/baseline-classpath.txt)" \
  --patch-module com.shangin.fractal=target/test-classes \
  --enable-native-access=javafx.graphics,org.lwjgl \
  -Djavafx.cachedir=/tmp/fractallens-javafx-cache \
  -Dbaseline.output=target/baseline-input-check \
  -Dbaseline.revision="$(git rev-parse HEAD)" \
  -Dbaseline.sizes=480x270 -Dbaseline.warmups=1 -Dbaseline.runs=3 \
  -m com.shangin.fractal/com.shangin.fractal.render.BaselineInputBenchmark

python3 scripts/summarize_baseline_input.py target/baseline-input-check \
  target/baseline-input-check/summary.csv
```

Defaults select all fixtures, five gestures for single-step fixtures, both
requested modes, 480x270, one warmup and three samples, plus a first sequence.
`baseline.fixtures`, `baseline.sizes`, `baseline.fx.modes` and
`baseline.input.gestures` narrow selection. Gesture selection applies only to
ordinary single-step fixtures; navigation and replacement retain their defined
scripts. Unknown or duplicate gestures/modes and incompatible dimensions fail.
Use `-Dprism.verbose=true` separately to record the actual initialized pipeline;
a requested pipeline property is not proof of the active renderer.

```sh
mvn clean test
mvn -q -Dfractal.fx.tests=true -Djavafx.cachedir=/tmp/fractallens-javafx-cache \
  -Dtest=BaselineInputBenchmarkTest test
```

See [saved validation](BASELINE_INPUT_VALIDATION.md). This step connects the
handlers and comparable job descriptions. Optional
[scheduling and base-worker cancellation diagnostics](BASELINE_SCHEDULING_BENCHMARK.md)
now extend the same trace. Allocation attribution and sustained memory/thermal
measurements remain open in roadmap 9.1.

The matrix exposed and regression-tested an `int` overflow in scaled-exponent
pan grid snapping. Coordinate alignment now keeps arbitrary-size integer pixel
offsets in `BigDecimal`; normal integer shifts retain their prior results.
