# Roadmap 9.1 JavaFX publication baseline

[`BaselineFxBenchmark`](../src/test/java/com/shangin/fractal/render/BaselineFxBenchmark.java)
uses the same `9.1-v1` fixture objects and exact [manifest](BASELINE_FIXTURES.csv)
as the [headless benchmark](BASELINE_BENCHMARK.md). It exercises the production
`FractalRenderService`, CPU precision selection, frame reuse, `FractalSurface`
PixelBuffers and `InteractiveAntialiasService` in a visible window. The harness
lives in test sources. The long run also exposed and led to a production fix:
detached surfaces now unsubscribe from old Scene/Window scale listeners, allowing
their buffers and sample frames to be collected. Rendering policy is unchanged.

This is a component-pipeline benchmark. It starts from an exact render request,
not a `FractalView` input event, and does not include camera fitting, input
debounce, OS input delivery or physical scanout. The [production input runner](BASELINE_INPUT_BENCHMARK.md) now connects those
handlers to the shared fixtures, preserving actual camera/controller requests.
The older [interaction benchmark](INTERACTION_LATENCY.md) retains its separate
screen-marker capture experiment.

## Presentation and cache contract

Every selected fixture runs in requested `FAST` and `REFINED` modes. Direct AA
uses the requested presentation mode. Base-only and deep work use `FAST` as the
effective mode: there is no refined base-only image to publish, and the
production controller also uses Fast presentation for deep rendering. Both
requested and effective modes are recorded. Do not interpret a deep row labelled
requested Refined as a different deep quality algorithm.

The matrix's caps, coordinates, formula/trap parameters, AA grid/pattern and
accuracy are used without UI defaults or dimension substitution. Histogram
scenes show the production smooth preview and then apply the complete-frame
histogram mapping. Refined base samples can finish without being published;
their publication timestamps remain `-1`. Refined first publication normally
comes from an AA tile. Base-only rows publish their base frame in either
requested mode.

The surface is new at the start of every sequence; staging buffer preparation
is timed inside the request, while surface-node/window setup is outside it.
Navigation steps retain the surface within the sequence. Base sample caches
follow active-then-retained planning, including complete reuse on reverse zoom.
The backend and AA service live for each fixture/mode, so reference caches and
worker/JIT state warm across repetitions. `cold_fixture` denotes the first
sequence on those new services, not cold JVM startup or guaranteed cold GPU
driver state. Even measured sequences start with fresh surface buffers.

Retained Julia AA first prepares sample/base-phase caches without publishing
that preparation to the surface. Only the second refinement is the measured
AA interval; preparation remains separately reported and included in the full
request time. Other new frames start with empty AA caches. The harness passes
empty refined-pixel snapshots, so it does not claim retained-AA navigation
coverage beyond the matrix's same-frame case.

Render dimensions are physical buffer pixels, and must be even and representable
at the actual window output scale. An incompatible size fails rather than being
rounded. The window is bounded by the display's visual bounds; a large buffer
can be scaled to the available surface area. Target-buffer publication remains
measured, not one-to-one panel-pixel presentation or native resize-event latency.

## Timing fields

All timestamps are monotonic and belong to a unique `request` identity. The
first-region timestamp is captured at the backend callback; surface publication
uses the existing opt-in `InteractionLatency` milestones. Pulse observations
are attached to the active request only. Queued work from a cancelled service
generation cannot be attributed to its successor.

| Scope | Boundary |
| --- | --- |
| `fx_dispatch_ms` | Driver request → FX thread entry |
| `plan_ms` | FX entry → sample-frame allocation/reuse planning complete |
| `first_backend_region_ms` | Request → first completed backend region; absent for fully reused work |
| `backend_ms` | Backend entry → return/worker drain; excludes FX queueing and publication |
| `base_complete_ms` | Request → base-completion callback on the FX thread |
| `first_base_publish_ms` | Request → first target base pixels published to the visible staging image |
| `base_full_publish_ms` | Request → base frame promoted in Fast; absent for hidden Refined base |
| `aa_prepare_ms` | Same-frame cache preparation, when requested |
| `aa_ms` | Measured AA call → success callback on FX, including tile publication |
| `first_aa_publish_ms` | Request → first measured AA tile published |
| `first_aa_tile_ms` | Measured AA call → first AA tile published, excluding same-frame cache preparation |
| `first_visible_publish_ms` | Request → first target base, AA or promoted-frame publication |
| `full_publish_ms` | Request → complete target publication, including enabled AA/preparation |
| `first_post_layout_ms` / `full_post_layout_ms` | Request → next scene post-layout observation after the corresponding software publication |
| `cancel_request_ms` / `cancel_tail_ms` | Request → first backend-region cancellation trigger / trigger → backend exit |
| `base_fx_work_ms` / `aa_fx_work_ms` | Summed work inside base/AA publication callbacks; exclude initial surface/reuse preparation |

`-1` means absent/unmeasured, never zero. Intervals overlap and are not additive
frame costs. Post-layout observations are JavaFX software scheduling boundaries,
not GPU completion, compositor readback, physical scanout or photon latency.
No screen capture is performed. `full_publish_ms` for a prepared AA case includes
its extra preparation and must not be compared to an ordinary full-AA request.

Heap observations and GC counter deltas surround each request, including the
untimed final buffer copy and pulse wait. They are used-heap observations, not
allocated-byte counts, live retained size, process RSS or thermal measurements.
The benchmark never forces GC; only the isolated detached-surface regression
uses explicit collection to check ownership while the window stays alive.

Cancellation uses the same first-backend-region trigger as the headless matrix.
The backend cooperatively drains its workers and prevents the intentionally
partial result from entering the service's success path. No cancelled result
is promoted. This is not a measurement of user-event cancellation, AA cancellation
or cancellation during reference construction.

## Reproduction

Compile once, then run each benchmark in a separate process, sequentially.
Use the **same Java executable** for both runners; `java` on PATH and Maven can
resolve different installations. The exact executable used for the saved run
is recorded in the [validation report](BASELINE_FX_VALIDATION.md).

```sh
mvn -q -DskipTests test-compile dependency:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile=target/baseline-classpath.txt

# Set this to the JDK you intend to compare; check it against mvn -v.
BASELINE_JAVA=/path/to/jdk/bin/java

"$BASELINE_JAVA" -Xmx4g \
  --module-path "target/classes:$(cat target/baseline-classpath.txt)" \
  --patch-module com.shangin.fractal=target/test-classes \
  --enable-native-access=javafx.graphics,org.lwjgl \
  -Djavafx.cachedir=/tmp/fractalui-javafx-cache \
  -Dbaseline.output=target/baseline-fx-check \
  -Dbaseline.revision="$(git rev-parse HEAD)" -Dbaseline.label=current \
  -Dbaseline.sizes=480x270,1512x982,3024x1964 \
  -Dbaseline.warmups=1 -Dbaseline.runs=3 \
  -m com.shangin.fractal/com.shangin.fractal.render.BaselineFxBenchmark

python3 scripts/summarize_baseline_fx.py target/baseline-fx-check \
  target/baseline-fx-check/summary.csv
```

For the headless process, use `BaselineBenchmark` as the main class and a new
output directory, with the same size/fixture/repetition/runtime settings. The
module-path separator above is for macOS/Linux. A classpath launch also works
but emits JavaFX's unsupported-configuration warning; the saved measurements
use the modular launch.

Common selection properties are shared with the headless runner. Defaults are
all 28 fixtures at 480x270, one warmup and three measured repetitions, plus the
initial cold-fixture sequence. `baseline.fx.modes` defaults to `FAST,REFINED`;
unknown or repeated modes fail. A new output directory is required. Both
runners share the same process lock; other timing suites must still be stopped
separately. The window closes when the run ends.

For a graphics attribution/validation run, `-Dprism.verbose=true` records the
**actual** initialized Prism pipeline in the launch log. The environment's
`requested_pipeline` property alone does not identify the actual pipeline.
Run with graphical access; a sandbox software fallback is not a native baseline.
Keep startup diagnostics and JFR/native profiles separate from final timing
decisions, and retain the original log with saved validation results.

## Verification and limits

After all timed repetitions of each fixture/mode, the runner creates fresh
same-grid CPU controls for every step of the final sequence. It compares every
iteration, smooth value, escape flag and trap distance, then every final ARGB
pixel. This covers full base and AA output, not just fingerprint equality.
Control work and PixelBuffer readback happen outside the measured intervals.
Control work can warm later fixtures; use one selected fixture per fresh JVM
when process-cold behavior matters.

Raw rows include fingerprints for every repetition. `SUCCESS` is written only
after all controls pass. The summarizer requires that marker, exact manifest
membership/caps, all declared cold/warm/measured repetitions and modes, unique
request identities, consistent publication boundaries and repeatable completed
frame fingerprints. It reports per-scope sample medians, nearest-rank p95 and
maxima, and refuses to overwrite output.

Regression commands:

```sh
mvn clean test
mvn -q -Dfractal.fx.tests=true -Djavafx.cachedir=/tmp/fractalui-javafx-cache \
  -Dtest=BaselineFxBenchmarkTest test
```

The second command needs a real graphical session. The listener-lifetime fix
does not change the application rendering policy. This work makes JavaFX publication
inputs comparable; it does not establish an optimization win, satisfy the
30-pair/multiple-start decision gate or measure allocation, scheduling internals,
long-duration memory/thermal behavior or physical scanout.
