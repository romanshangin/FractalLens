# Roadmap 9.1 scheduling and cancellation diagnostics

> Publication scope: use this protocol for fresh runs in a new output directory.
> Keep raw logs and diagnostic captures private; publish only reviewed summaries
> and necessary sanitized evidence. See [evidence provenance](../docs/development/PUBLICATION.md).

`-Dfractal.render.diagnostics=true` at JVM startup enables request-owned CPU
scheduling diagnostics. The default remains off. Direct and perturbation pools
keep their existing fixed-pool scheduling policy; disabled runs use the original
JDK executors. This is attribution instrumentation, with extra clocks, counters,
locking and task wrappers. It is not an uninstrumented performance decision run.

## Boundaries and ownership

`FractalRenderService` assigns each request its service generation. A listener
receives the diagnostics at submission and can await `completion()` for an
immutable snapshot. Completion requires coordinator exit **and** termination of
all registered worker tasks. A cancelled `Future` alone cannot satisfy it.
Cancelled tasks that have not been claimed by a worker are counted separately;
a claimed wrapper may still find its callable cancelled before entering it.
The original callback generation checks remain in force.

Cancellation is timestamped before generation invalidation, because another
thread can observe invalidation immediately. `cancel_observed` is the first true
cancellation-supplier check; it can be absent when interruption stops the
coordinator or no subsequent check occurs. `coordinator_interrupt_observed`
separately records interruption caught by the render service.

The snapshot records:

- Request submission, coordinator start/exit, backend entry/exit and request
  drain. A queued coordinator cancelled before execution has no backend times.
- Task planning, direct tile ordering and accumulated direct reusable-mask
  scanning. Perturbation also records reference orbit, coordinate-delta and
  BLA preparation. These preparation phases can end early on cancellation.
- Registered, submitted, started and skipped tasks; first submission/start,
  last worker exit, and aggregate/max worker queue and run intervals.
- Cancellation request, observation, backend exit, last worker exit and drain.
  Worker exit may precede cancellation; a nonexistent post-cancel worker tail
  stays absent rather than becoming a negative duration or zero-cost claim.

Direct worker tasks are tiles; perturbation tasks are worker loops that drain a
shared tile queue. Their task counts and run times have different units of work.
Direct `candidate_tiles` includes tiles later found ready; perturbation counts
only tiles passing its readiness filter. Zero planned tasks is valid for a
fully reused frame. Missing counters mean zero; missing milestones mean absent.

Queue intervals include wrapper/instrumentation overhead. Coordinator queue
also includes submission setup and listener delivery. Worker run sums overlap
across threads; neither they nor the nested preparation/scan intervals may be
added to frame wall time or interpreted as process CPU utilization. Request
drain excludes downstream JavaFX/AA work. These diagnostics cover the CPU base
backends, not AA worker drain, GPU synchronization or physical presentation.

## Shared matrix and real input

`BaselineSchedulingBenchmark` runs canonical `9.1-v1` jobs through the production
render service. It retains the shared fixture selectors, dimensions, source
reuse policy and cross-process baseline lock. It measures base calculation only,
even for fixtures whose manifest specifies AA. CPU controls run outside the
measurement: every ready sample is compared exactly, including escaped,
iterations, smooth and trap values. The control preserves previously verified
retained samples and independently calculates missing pixels. Partial cancelled
frames are checked only at ready pixels, after all workers have drained.

`first_region` cancels synchronously from the first backend region callback.
`baseline.cancelPlanning=true` adds a second request for every fully reused
result, polling for planning entry before cancellation. Missing that window
fails the run. This deliberately exercises an in-progress coordinator scan;
it is distinct from cancellation after progress or by a real gesture.

The existing `BaselineInputBenchmark` records the same snapshots as
`diagnostic_*` events. Attachment captures the original recording epoch, input
and render ID. Old worker exits cannot inherit a replacement gesture's identity
or enter a later recording. The driver awaits diagnostic drain off the FX thread
before closing a trial; UI completion and post-layout timestamps remain their
original values. Base requests bypassed by the controller have no invented
backend diagnostics. Seed rendering remains outside input recording.
The existing sample/ARGB controls and actual source/target manifests still apply.

Raw absolute milestones use `System.nanoTime`. Diagnostic counter/duration
values occupy the event `duration_nanos` column, with their event timestamp set
to request drain; `diagnostic_generation` is an ID, not a duration. Events can
be appended after newer input events, so use their timestamps and identities,
not append order. Use the scheduling summarizer to interpret this schema.

## Reproduction

Run suites sequentially. All output directories must be new.

```sh
mvn -q -DskipTests test-compile dependency:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile=target/baseline-classpath.txt

java -Xmx4g --module-path "target/classes:$(cat target/baseline-classpath.txt)" \
  --patch-module com.shangin.fractal=target/test-classes \
  --enable-native-access=javafx.graphics,org.lwjgl \
  -Dfractal.render.diagnostics=true \
  -Dbaseline.fixtures=seahorse-fixed,direct-deep-reverse,pan-25,resize-then-drag,cancel-direct,cancel-deep \
  -Dbaseline.sizes=480x270 -Dbaseline.warmups=0 -Dbaseline.runs=3 \
  -Dbaseline.output=target/scheduling-check \
  -m com.shangin.fractal/com.shangin.fractal.render.BaselineSchedulingBenchmark

python3 scripts/summarize_baseline_scheduling.py target/scheduling-check
```

For the planning-cancellation probe, select `direct-deep-reverse`,
`3024x1964`, `baseline.cancelPlanning=true` and a new output directory. One
first sequence and one sample suffice for validating this long scan; they do
not characterize statistical tails.

That polling probe targets the historical long complete-mask scan. With the
[bounded-mask candidate](VALIDITY_MASK_OPTIMIZATION.md), complete-frame planning
may finish before the polling thread runs; a missed trigger must still fail,
not be recorded as zero cancellation latency. Use the deterministic
`RenderDiagnosticsTest.cancellationInsideMaskPlanningDrainsWithoutSubmittingWorkers`
regression for cancellation inside preparation, and keep `cancelPlanning=false`
for the ordinary post-first-region diagnostic matrix on the candidate.

For the input scope, use the same startup switch with
`com.shangin.fractal.render.BaselineInputBenchmark`, a graphical session and
`-Djavafx.cachedir=/tmp/fractallens-javafx-cache`. The scheduling summarizer also
accepts its output directory and runs the existing strict input validator.
`SUCCESS` is a runner conformance marker; the summarizer additionally rejects
missing matrix cases, missing submitted-request snapshots, impossible event
order and incomplete worker accounting. Run both before accepting a dataset.
Its derived `scheduling-summary.csv` may be regenerated from immutable raw CSVs.

```sh
mvn clean test
mvn -q -Dfractal.fx.tests=true -Dfractal.render.diagnostics=true \
  -Djavafx.cachedir=/tmp/fractallens-javafx-cache \
  -Dtest=BaselineInputBenchmarkTest test
python3 -m unittest discover -s scripts -p 'test_summarize_baseline*.py'
```

See [saved validation and the next decision](BASELINE_SCHEDULING_VALIDATION.md).
Allocation attribution, sustained memory/thermal behavior and uninstrumented
30-pair/multiple-process gates remain open in 9.1.
