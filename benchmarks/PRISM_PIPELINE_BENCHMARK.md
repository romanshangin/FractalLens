# Prism pipeline comparison: ES2 (OpenGL) and Metal on macOS

> Publication scope: use this protocol for fresh runs in a new output directory.
> Keep raw logs, CSV recordings and JFR files private; publish only reviewed
> summaries. See [evidence provenance](../docs/development/PUBLICATION.md).

JavaFX 26 tries the ES2 (OpenGL) pipeline first on macOS and Metal (`mtl`)
second. The application does not choose one, so it currently runs on ES2. On
ES2, a frame finished while the window is on another macOS Space can stay
invisible after returning to that Space; the same scenario was not reproduced
with `-Dprism.order=mtl` in a manual check. This protocol measures whether
Metal can replace ES2 without making publication or visible updates slower.

One frozen build runs under both pipelines. A always uses
`-Dprism.order=es2`, B always uses `-Dprism.order=mtl`; each JVM requests a
single pipeline, so a failed initialization cannot silently fall back to
another one. Ratios are B/A, Metal over ES2.

The comparison has three parts. Only the first two produce timing verdicts.

| Part | Driver | What it reaches | Policy |
| --- | --- | --- | --- |
| Publication pairs | [`BaselineFxBenchmark`](BASELINE_FX_BENCHMARK.md) through `run_baseline_pairs.py` | FX-thread publication and the next post-layout pulse | [`prism-pipeline-fx.json`](policies/prism-pipeline-fx.json) |
| Screen latency pairs | [`InteractionLatencyBenchmark`](INTERACTION_LATENCY.md) through `run_pipeline_latency.py` | JavaFX Robot capture of a publication marker (compositor proxy) | [`prism-pipeline-latency.json`](policies/prism-pipeline-latency.json) |
| Pulse phases | The same latency driver with JFR, without screen capture | `javafx.PulsePhase` durations on the FX and render threads | Diagnostic runs declared by the latency policy |

The publication pairs measure software boundaries on the FX thread. Texture
upload, drawing and presentation happen on the render thread, so these metrics
can miss a pipeline difference; they detect contention or pulse delays that
reach the FX thread. The screen pairs include rendering and compositing up to a
captured marker, which is the closest available proxy for a visible update. The
pulse phases attribute render-thread time (`Painting`, `Presenting` and the
per-pulse total) but run under JFR and are therefore excluded from verdicts.

## Running

Freeze one revision, then run the two campaigns sequentially without other
timing work, builds or tests. Both campaigns open windows; the latency campaign
keeps a window on top and needs screen-recording permission for the selected
`java` executable. Do not use the computer while it runs. On macOS, prefix the
runner with `caffeinate -d -i -u --` so display sleep, and the screen lock that
follows it, cannot hide the window; a hidden window aborts the latency driver.

```shell
python3 scripts/benchmarks/prepare_baseline_build.py HEAD /tmp/fractallens-prism-build \
  --java /path/to/jdk/bin/java --maven "$PWD/mvnw"

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmarks/run_baseline_pairs.py \
  benchmarks/policies/prism-pipeline-fx.json NEW_FX_OUTPUT \
  --a /tmp/fractallens-prism-build --b /tmp/fractallens-prism-build \
  --java /path/to/jdk/bin/java --thermal /tmp/fractallens-baseline-thermal
PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmarks/summarize_baseline_pairs.py NEW_FX_OUTPUT

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmarks/run_pipeline_latency.py \
  benchmarks/policies/prism-pipeline-latency.json NEW_LATENCY_OUTPUT \
  --build /tmp/fractallens-prism-build \
  --java /path/to/jdk/bin/java --thermal /tmp/fractallens-baseline-thermal
PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmarks/summarize_pipeline_latency.py \
  NEW_LATENCY_OUTPUT --jfr /path/to/jdk/bin/jfr
```

The thermal helper is optional; build it as described in
[the paired protocol](BASELINE_PAIRS_BENCHMARK.md).

## Configuration campaigns

`run_baseline_pairs.py` accepts `purpose: configuration`: both snapshots must be
the same compiled build, `jvm_options` declares exactly one
`-Dprism.order=es2|mtl|sw` option per label and `expected_pipeline` must match it.
Every launch must carry its declared option, and its log (`-Dprism.verbose=true`)
must report exactly that initialized pipeline. Control-only policies are allowed:
the question is non-inferiority, not a required improvement. All other
validation of the [paired protocol](BASELINE_PAIRS_BENCHMARK.md) applies,
including AB/BA alternation, frozen policy hashes and matching sample and ARGB
fingerprints, which are computed from buffers and must be identical across
pipelines.

`run_pipeline_latency.py` alternates AB/BA JVMs of the interaction-latency
driver with screen observation for every mode (Refined, Fast, Base only) and the
declared gestures. It uses the same native-access options as the macOS
`javafx:run` profile and exports the benchmark package to the JavaFX launcher.
After the timed processes, it runs the declared number of JFR diagnostic JVM
pairs without screen capture. The analyzer requires the declared pipeline in
every log and recording header, screen observation in timed runs, one window
size and output scale for the whole campaign, and every declared trial. It
pairs trials by process pair, mode, gesture and iteration. A trial whose marker
is absent on either side is counted in `missing_pairs`, never as zero; a group
with fewer than 30 pairs is inconclusive. Every declared mode, gesture and
metric is reported, so a metric absent from every trial stays in the result as
an inconclusive group with zero pairs.

## Decision rule

Every declared metric is a control: the hierarchical bootstrap 95% interval of
the median B/A ratio must stay at or below 1.05. `timing_verdict` is `pass` only
when every group passes, `fail` when any group's interval lies entirely above
its limit and `inconclusive` otherwise. A timing pass does not by itself switch
the application to Metal. A switch also needs the visible checks
([Space switching](SPACE_SWITCH_VALIDATION.md), resize, fullscreen, light and
dark appearance) and the portable
suite, and it applies only to the measured Mac. Windows and Linux do not use
Metal.

## Predeclared confirmation campaign (P1.7)

The first campaign left 37 of 103 groups inconclusive. The confirmation
policies retain the entire original matrix, including the 66 passing groups;
no group is selected by its observed median. The original policies and results
remain historical evidence. The new policies change only `process_pairs`:

| Part | Confirmation policy | JVM pairs | Samples per group | Warmups per case |
| --- | --- | ---: | ---: | ---: |
| Publication | [`prism-pipeline-fx-confirm.json`](policies/prism-pipeline-fx-confirm.json) | 60 | 180 | 3 |
| Screen latency | [`prism-pipeline-latency-confirm.json`](policies/prism-pipeline-latency-confirm.json) | 30 | 150 | 2 |

Publication has twice the original independent process pairs; screen latency
has five times the original independent process pairs. Within-JVM sample
counts remain 3 and 5 respectively; warmups apply to each fixture/mode or
gesture/mode case, not to the JVM as a whole. More pairs improve precision but do not
guarantee a decisive result. Both policies retain AB/BA alternation, all
fixtures, sizes, modes, gestures, metrics, pipeline checks and the 1.05 limit.
The latency campaign retains its separate JFR diagnostic pair; those diagnostics
do not explain the 3024x1964 publication outliers because they use a different
workload and window size.

Freeze revision `f8895b6c136ebfdf9eb8aebc39622196d36d99e2` once for both
pipelines, using the same JDK and dependencies for compilation and execution.
Use the commands in **Running**, substituting that revision for `HEAD`, the
confirmation policies for the original policies, and fresh output directories.
Prefix **both** timing runners with `caffeinate -d -i -u --`. Run them sequentially
in an active graphical macOS session with AC power, nominal thermal pressure,
a configured thermal helper, and no competing builds, tests or desktop use.
Keep the latency window at 1200x760 logical points and output scale 2, matching
the first campaign. Inspect actual pipeline initialization and the recorded
environment rather than assuming requested settings were applied.

The budget is one complete campaign per confirmation policy. Do not inspect
intermediate timing verdicts, extend the sample budget, relax a limit, drop a
group, or pool historical samples into the confirmation result. If a campaign
aborts because of visibility, permission, pipeline, power or thermal problems,
retain it privately as invalid evidence, correct the environment, and restart
the same policy in a new directory. Never resume with selected process pairs.

Analyze each complete campaign independently using the existing analyzers and
the hierarchical bootstrap decision rule above. Report every group, including
missing pairs. A combined timing pass requires both campaigns to pass. Any
failing group rejects default activation under this performance gate. Otherwise,
any inconclusive group leaves the combined decision `inconclusive`: stop this
budget and request an explicit owner decision on the latency trade-off before
activation. A timing pass still leaves the other migration gates open.

Status: the publication campaign ran in full on 2026-10-28 and ended `fail`
(one failing group), which rejects default activation under this gate; the
screen-latency campaign has not been executed. See
[the confirmation results](PRISM_PIPELINE_RESULTS.md#confirmation-campaign-p17-2026-10-28).
The default pipeline is unchanged. Spaces presentation, fixture-specific JFR
attribution, long-session memory and the remaining migration checklist are
separate open requirements.

## Predeclared post-change publication campaign (P1.7)

Commit `2a19472` stops allocating the orbit-trap distance plane for built-in
presets without a trap, which removes the frame-preparation stall diagnosed
behind the failing confirmation group. This campaign measures that changed
implementation. It is a new measurement, not an extension of the confirmation
budget: the confirmation `fail` stays the historical result for `f8895b6`, and
no historical sample is pooled into this one.

- Policy: [`prism-pipeline-fx-confirm.json`](policies/prism-pipeline-fx-confirm.json),
  unchanged: 60 AB/BA process pairs, all 28 publication groups, the 1.05
  limit and the same pipeline checks.
- Build: freeze revision `2a1947297ec13bb23e9d956ff7f67ff009f299ea` once and use
  it for both pipelines, with the same JDK and dependencies as the
  confirmation campaign.
- Conditions and handling: the same as the confirmation campaign above,
  including `caffeinate -d -i -u --`, AC power, nominal thermal pressure, no
  competing work or desktop use, a fresh output directory, one complete
  campaign, no inspection of intermediate verdicts, and an invalid-evidence
  restart only for environment failures.
- Decision: the hierarchical bootstrap rule above. Any failing group rejects
  default activation under this gate. A publication pass or an inconclusive
  result does not complete the timing gate: the screen-latency confirmation
  campaign on the same build, or an explicit owner decision on it, is still
  required, and an inconclusive group leaves the combined decision
  `inconclusive`.

Status: declared before any timing run of revision `2a19472`; the campaign
then ran in full on 2026-10-28 and ended `fail` (25 pass, 2 inconclusive,
1512x982 Refined first visible publish fails). See
[the post-change results](PRISM_PIPELINE_RESULTS.md#post-change-publication-campaign-p17-2026-10-28).

## Sample-to-GC correlation diagnostics (P1.7)

`-Dbaseline.fx.jfr=true` enables a test-only
`com.shangin.fractal.BaselineFxSample` event for every cold, warmup and measured
request in `BaselineFxBenchmark`. It contains fixture, dimensions, requested and
effective mode, phase, run and request identity, plus raw `System.nanoTime`
boundaries for request, FX entry, planning completion, first backend region,
first visible publication, full post-layout and the untimed pixel snapshot.
Missing boundaries remain `-1`. `samples.csv` and its timing definitions are
unchanged. The flag is recorded in `environment.txt`; these diagnostic JVMs
are excluded from paired timing verdicts, including recordings started dynamically.

Events are allocated and committed after each measurement, before CSV hashing.
The event's JFR `begin()` is bracketed by two monotonic clock readings. This
maps the stored boundaries onto the recording timeline without a wall-clock
calibration or millisecond JVM-uptime rounding. The bracket width is retained
as clock uncertainty; a scheduling pause during calibration widens it instead
of producing false precision. It is not an independent calibration of JFR's
hardware-clock resolution or clock drift. No JFR dependency is added to the
production module: test compilation and diagnostic modular launches grant
read access to the JDK's `jdk.jfr` module only.

Compile once:

```shell
./mvnw -DskipTests test-compile dependency:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile=target/baseline-classpath.txt
```

Use the modular launch in [the FX protocol](BASELINE_FX_BENCHMARK.md), adding:

```shell
--add-modules=jdk.jfr --add-reads=com.shangin.fractal=jdk.jfr \
-Dbaseline.fx.jfr=true \
-XX:StartFlightRecording=settings=profile,filename=NEW_PRIVATE_RECORDING.jfr,dumponexit=true
```

The recording must enable `com.shangin.fractal.BaselineFxSample` and outer
`jdk.GCPhasePause` events with zero threshold (the JDK profile defaults include
them). Run in an active graphical session, with a fresh output/cache directory
per JVM. Retain logs confirming the requested Prism pipeline, successful
same-grid sample/ARGB conformance and the JDK/environment. Keep recordings,
logs, CSVs and launch commands private.

```shell
python3 scripts/benchmarks/correlate_fx_gc.py NEW_PRIVATE_RECORDING.jfr \
  NEW_PRIVATE_CORRELATION.csv --jfr /path/to/measured/jdk/bin/jfr \
  --samples NEW_PRIVATE_BENCHMARK_OUTPUT/samples.csv
```

The analyzer accepts one JVM recording at a time. It reports every recorded
phase and measured interval, including intervals with no overlapping pause.
Use `phase=sample` and join with `samples.csv` by `request` for the measured
sample diagnosis. It uses only outer `jdk.GCPhasePause` events, avoiding nested
phase double counting; concurrent GC is not a stop-the-world pause. Output
includes the number of definite and possible overlaps, possible GC IDs and
conservative lower/upper bounds on overlapping pause time in nanoseconds.
Counts include definite overlaps in the possible set. Half-open intervals
exclude touching endpoints. Missing milestones are omitted; reused publication
before new backend work has no backend-to-publication interval. A recording
with no sample or no GC pause events is rejected: absence alone cannot establish
recording coverage. Check recording coverage/thresholds before interpreting
zero overlap as absence of a pause. The analyzer requires the matching `samples.csv` and its successful conformance
marker. It rejects missing/duplicate requests, differing fixture identities and
mismatched timing boundaries, so a truncated or unrelated recording cannot
silently provide partial sample coverage.

Planning, request-to-first-visible, request-to-full-post-layout and snapshot
intervals can overlap each other; do not add their GC times. The snapshot is
outside first-visible timing, but can alter heap conditions for later requests.
Temporal overlap identifies where a stop-the-world pause fell; it does not
identify its triggering allocation or prove that the pipeline caused it.
JFR and diagnostic events change allocation/timing, so these runs never pass
or replace a performance gate, and cannot retrospectively establish individual
pause attribution in the historical recordings.

Diagnostic budget for this implementation: three fresh AB/BA JVM pairs with
ES2/Metal, the confirmation publication workload (both sizes, all three
fixtures, FAST then REFINED, three warmups and three measured runs, 4 GiB heap).
Freeze the compiled candidate for all six JVMs. This is an exploratory
correlation run, with no retain/reject timing verdict or extension based on
results. Record power/thermal conditions and any uncontrolled competing desktop
work as diagnostic limits. Existing campaign `fail` results remain unchanged.

## Fresh-frame planning decomposition (diagnostic, P1.7)

This is a new diagnostic question and budget after the completed sample/GC
correlation series, not an extension of that series or a performance verdict.
Determine whether fresh-frame planning time sits in coordinate-grid creation or
frame storage/mask creation, and whether long wall times also consume FX-thread
CPU time. Production code and default Prism selection are unchanged.

With `baseline.fx.jfr=true`, the harness splits the existing fresh-frame factory
sequence into `RenderGrid.from(...)` followed by `RenderFrame.create(job, grid)`.
It uses the production overloads, preserving exact coordinates, parity, orbit-trap
storage selection and the ready mask. The normal uninstrumented path still calls
`RenderFrame.create(job)` directly. Reuse planning remains unchanged and has no
subinterval measurements; do not interpret its missing fields as zero cost.
The sample JFR event's `planningVersion=1` adds grid and frame start/end clocks
and thread CPU durations, plus the constructing FX thread's Java thread ID for
matching trace/allocation events. Old recordings remain readable without these fields.
The analyzer emits `grid_creation` and `frame_storage_creation` rows and rejects
incomplete, reversed or out-of-parent boundaries. `thread_cpu_ns=-1` means absent
or unsupported; CPU availability is recorded in the environment. CPU accounting
is not enabled globally by the harness if it was disabled by the runtime.

Wall clocks enclose the thread CPU reads. Their overhead and CPU timer resolution
are part of this diagnostic; wall minus CPU time is not an exact scheduler-wait
measurement. Neither CPU time nor GC overlap alone distinguishes allocation,
zero initialization, page faults, native allocator work or scheduling. Do not
subtract independent medians or sum overlapping trace/GC intervals.

For the measured JDK 26 diagnostic runtime, generate a private JFR configuration
from `profile.jfc` with these settings using its `jfr configure` command:

- `method-trace=com.shangin.fractal.render.FractalData::<init>;com.shangin.fractal.render.ValidityMask::<init>`;
- `jdk.MethodTrace#threshold=0ns`, `jdk.MethodTrace#stackTrace=false`;
- `jdk.ObjectAllocationOutsideTLAB#enabled=true` and `#stackTrace=true`;
- `jdk.SafepointBegin#enabled=true`, `#threshold=0ns`, and the same for `jdk.SafepointEnd`.

Pass the generated configuration as `StartFlightRecording=settings=...`, with
fresh recording, output and cache paths. Retain the configuration and its hash.
`jdk.MethodTrace` is a runtime-specific diagnostic capability; it is not required
by the production module or the portable JDK 25 build. Constructor trace durations
include instrumentation and nested work, not just allocation. Allocation events
are point observations at allocation completion, not allocation-duration timers.
Use their thread, timestamp, stack site and size as supporting evidence only.
Safepoint begin/end events must be paired by safepoint ID; a begin-event duration
alone does not cover the whole safepoint. Nested safepoints and GC observations
must not be added together as independent stalls.

Predeclared budget: three fresh AB/BA process pairs, both pipelines on one frozen
build, the same confirmation workload, 1512x982 and 3024x1964, FAST then REFINED,
three warmups and three measured sequences after each cold sequence, 4 GiB heap.
Run sequentially in the active desktop session with `caffeinate`, AC power,
nominal thermal pressure, and no competing build/test work. Validate actual
pipelines, every request identity/boundary, and exact samples/ARGB fingerprints.
Inspect every overview group and retain per-JVM observations. Interpret only
these diagnostic samples; do not pool them with earlier runs, extend the budget
after observing timing, promote an optimization or relabel historical failures.
If an environment failure invalidates the run, retain it privately and restart
the same budget in a fresh directory after fixing the environment. Small
non-graphical configuration/schema smoke checks do not belong to this budget.
Raw traces and working analysis remain private; publish a sanitized summary and
an explicit `inconclusive` decision if the cause remains unresolved.

## Bounded worker preparation experiment (P1.7)

This benchmark-only experiment tests whether moving fresh-frame construction
from the FX thread to one worker reduces FX work without delaying publication.
It is not an application feature, a controller integration or a new Metal
promotion gate. The production `FractalRenderController`, reuse planner,
render service, AA lifecycle and default pipeline remain unchanged.

`BaselineFxBenchmark` accepts `baseline.fx.preparation=fx|worker` (default `fx`).
Both sides use the same compiled driver; only this property differs. The worker
variant invokes the existing `RenderFrame.create(job)` after the request begins,
then resumes image setup on the FX queue. It does not prefetch before the timed
request, move surface operations to a worker or reconstruct rounded coordinates.
Reuse steps follow the existing FX planner on both sides. Source-frame reads
are therefore not moved across their existing worker/publication boundary.

`FreshFramePreparationExperiment` is in test sources. It owns one executor and
one drain loop, one latest pending request and one ready result. Pending work
has not allocated target storage. Replacement discards the old ready slot;
the single queued callback captures the preparer, not a target frame. It reads
the current ready slot at delivery. Generations suppress stale successes and
errors. Cancellation cannot interrupt a JVM array allocation: its drain stage
completes only after the actual factory exits, including superseded work.
`close()` is idempotent, clears pending/ready state, shuts down the worker and
never waits on FX. Publication and cancellation are linearized under the same
lock; callbacks must not wait for the preparation worker. This is an experiment
contract, not a validated replacement for the application controller lifecycle.

The CSV appends two fields without redefining the earlier fields:

| Field | Meaning |
| --- | --- |
| `fx_setup_ms` | Summed FX work from initial request dispatch and resumed setup through render submission; excludes the worker wait, later progress callbacks and pulse wait |
| `preparation_ms` | Worker factory wall time for a fresh frame, or `-1` for the FX control and reuse steps |

`plan_ms` remains FX entry to planning completion; in the worker variant this
wall interval includes queueing, allocation and return to FX. It is not the FX
busy-time target. Setup wall time is not a direct input-latency measurement:
global GC during a worker wait can still stop FX even outside the setup chunks.
First-visible and full-publication clocks still start before
the first FX dispatch, so the worker hop is included. A failed factory completes
the request exceptionally, including cancellation fixtures awaiting their first
backend region. Pixel checks and hashes use the unchanged CPU controls.

The [predeclared policy](policies/p17-fresh-frame-preparation.json) fixes Metal,
1512x982 and 3024x1964, overview/regular-AA/pan-25, FAST then REFINED, six AB/BA
process pairs, five samples after three warmups and one cold sequence per case,
and a 4 GiB heap. That gives 30 matched observations per timing group. A is
`fx`, B is `worker`; JFR, constructor tracing, scheduling profiling and GPU
calculation are disabled. Use one frozen compiled build and dependency set,
record their hashes before/after, the exact policy hash in each launch, actual
`MTLPipeline`, and AC/nominal power/thermal observations before and after each
JVM. Run sequentially in the desktop session with `caffeinate`, new output/cache
paths and no competing builds, tests or desktop work.

Targets require an upper hierarchical 95% ratio bound <= 0.90 for FX setup in
every fresh-frame group. Controls require <= 1.05 for reuse FX setup, first
visible publish, full publish, first/full post-layout, and the first AA tile in
the regular-AA case. This is 84 declared timing groups. Statistics reuse the
existing 5,000-resample seeded process-pair-then-sample bootstrap. Do not select
only favorable groups or inspect partial timing verdicts. The observed memory
budget requires every one of the six B/A process-peak RSS ratios <= 1.05; this
is a resource cap for the campaign, not a confidence interval, long-session
memory acceptance or proof that no leak exists. Boundary/concurrency tests
separately check the bounded slots under burst input and delayed FX delivery.

The dedicated analyzer consumes `policy.json`, `build-before.json`,
`build-after.json`, launch records, raw driver directories/logs and a completion
marker from a full run. Every launch carries its policy hash, order, command,
power/thermal observations and `max_rss_bytes` from `wait4`. It validates the
full budget, actual runtime/pipeline, unchanged build/manifest, exact paired
fingerprints, new timing scopes and all declared cases before analysis. The
expected matrix is the full product of policy fixtures/sizes and their canonical
steps/actions from `BASELINE_FIXTURES.csv`; it is not inferred from surviving
run rows. Deleting the same fixture/size or step from every JVM is an error.

The public `run_frame_preparation.py` runner writes these inputs, using the
existing frozen-build verifier, child-process/RSS collector and power/thermal
helpers. It preflights the fixture manifest without JavaFX, preserves failed-run
evidence, and writes `CAMPAIGN_COMPLETE` only after all JVMs, paired coverage and
frozen inputs validate. Existing output directories are never overwritten.

Freeze a revision containing the worker benchmark sources (after those sources
have been committed), using the same JDK for build and execution. The revision
freezer excludes untracked files; its `--working-tree` mode includes tracked
edits only. Commands below run from the repository root. All output is private
working evidence, not automatically selected publication material:

```shell
P17_JAVA=/path/to/jdk/bin/java
P17_BUILD=/tmp/fractallens-p17-preparation-build
P17_THERMAL=/tmp/fractallens-p17-preparation-thermal
python3 scripts/benchmarks/prepare_baseline_build.py REVISION_CONTAINING_WORKER_EXPERIMENT \
  "$P17_BUILD" --java "$P17_JAVA" --maven "$PWD/mvnw"
swiftc scripts/benchmarks/baseline_thermal.swift -o "$P17_THERMAL"

caffeinate -d -i -u -- python3 scripts/benchmarks/run_frame_preparation.py \
  benchmarks/policies/p17-fresh-frame-preparation.json NEW_PRIVATE_OUTPUT \
  --build "$P17_BUILD" --java "$P17_JAVA" --thermal "$P17_THERMAL"
python3 scripts/benchmarks/analyze_frame_preparation.py NEW_PRIVATE_OUTPUT
```

A separate fresh output directory can validate the frozen build and real Java
fixture matrix without a display or any measurement JVMs:

```shell
python3 scripts/benchmarks/run_frame_preparation.py \
  benchmarks/policies/p17-fresh-frame-preparation.json NEW_PRIVATE_PREFLIGHT_OUTPUT \
  --build "$P17_BUILD" --java "$P17_JAVA" --preflight-only
```

This writes the planned AB/BA commands and `PREFLIGHT_COMPLETE`, never a campaign
completion marker. Measured execution remains macOS-specific and requires the
active graphical session, AC power and a configured nominal-thermal helper.
The public runner was added after the historical experiment. That original
campaign used a private working runner; its recorded source/policy identities
and result are preserved, not represented as a rerun with the public tool.

The normal paired/Prism analyzer rejects `frame_preparation=worker`; experimental
results cannot silently enter the established pipeline gate. The dedicated
result is `retain` only if every timing group and the RSS cap pass, `reject` if
any fails, and `inconclusive` otherwise. It always leaves application promotion
`not_evaluated`. Stop after this budget; an environment-invalid run is retained
privately and restarted from scratch in a new directory after correction.
Even `retain` would justify only a separately reviewed controller experiment
with current-generation publication, cancellation, reuse and platform checks.
A rejected/inconclusive variant remains outside the production path. Publish
only a sanitized summary; preserve the frozen inputs and raw evidence privately.

## Limits

- Results apply to the measured Mac model, macOS, JDK and JavaFX version.
- Screen observation is a capture of a 144x16 marker strip, not physical scanout
  or photon latency, and capture cost is included in each observation.
- FX publication metrics end on the FX thread and do not include GPU work.
- JFR pulse phases are diagnostic; JFR itself changes timing.
- Power source and thermal pressure are recorded only before and after each JVM.
