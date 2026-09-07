# Roadmap 9.1 baseline fixtures and headless scopes

Matrix version: `9.1-v1`. The reviewed [manifest](BASELINE_FIXTURES.csv)
contains 28 sequences / 37 steps at each of 480x270, 1512x982 and 3024x1964.
Dimensions are **render pixels**, not logical window dimensions. Resize adds
128x72 render pixels. This is a reproducibility foundation; it does not close
the scope-matched JavaFX, allocation/scheduling or sustained thermal baseline.

## Workloads and contracts

The executable source is
[`BaselineFixtures`](src/test/java/com/shangin/fractal/render/BaselineFixtures.java).
The runner uses production CPU backends, frame reuse, coloring and AA. It never
dispatches GPU work. All base samples require `CPU_REFERENCE`; there is no
approximate preview, symmetry override, palette animation or export in this run.
ICE uses offset zero. Formula parameters, trap, coloring mode and AA placement
are explicit in every manifest row.

| Fixtures | Purpose |
| --- | --- |
| Five preset overviews, exterior, seahorse fixed/adaptive | All implemented formulas; fixed 300 versus actual adaptive 632 at seahorse scale 0.024 |
| Mandelbrot/Julia regular and deterministic-jitter AA | Production candidate-only 4x4 sampling |
| Julia retained AA | Prepare the same frame once, then time a second refinement with retained sample/base-phase caches; preparation has its own scope |
| Mandelbrot histogram/trap AA | Histogram construction and cross-trap orbit features; no smooth-cache substitution |
| Deep glitch / deep glitch AA | Saved production stress coordinates at 1.6e-13, adaptive cap 2488; deep AA uses the production 2x2 then conditional 4x4 policy |
| Deep BLA / deep boundary | BLA-friendly production reference at 1e-30, fixed 2700; mixed boundary control at c=i, fixed 137 |
| Scaled exponent | c=2 at 1e-400, fixed 137; adjacent samples must remain distinct below double range |
| Direct/deep/reverse | Same precise center at 1e-9 → 1.6e-13 → 1e-9, recalculating the adaptive cap on each zoom and recovering the retained direct frame on return |
| Pan 5/25/90 | Integer shifts of floor(width × percentage / 100), with 95/75/10% nominal overlap |
| Pan reverse | Move by floor(width/4), then return to the exact original center |
| Resize then drag | Keep pixel spacing and cap across resize, then shift by (+17, −11) complex-plane pixel units |
| Cancel direct/deep | Request cooperative cancellation in the first completed backend-region callback; measure until backend return |

Regular/jitter positions are those in the production sampler at the recorded
revision; `aa_grid` records the grid policy, not a claim that every pixel is
supersampled. The deep-glitch name identifies the historical stress input;
it does not assert a current glitch/recovery count. Reprofiling is needed for
that attribution.

Decimal center/scale strings are authoritative. Do not reconstruct them through
`double`. Adaptive limits use base 300 and 50 iterations per zoom level with
the preset default scale. Pure pan/resize preserves the source limit; resize
therefore records `preserved_adaptive`, not a freshly evaluated policy. Tests
pin the complete manifest and verify both sides of the direct/deep dispatch
boundary at all three canonical dimensions. Intentional contract changes need
a new matrix version and a reviewed manifest diff.

## Running

Build and run a short headless validation (one fresh backend/cache instance per
fixture, one untuned cold sequence, one warmup, three measured sequences):

```shell
mvn -q -Pbaseline-benchmark -DskipTests verify \
  -Dbaseline.output=target/baseline-check \
  -Dbaseline.revision="$(git rev-parse HEAD)" \
  -Dbaseline.label=current
python3 scripts/summarize_baseline.py target/baseline-check target/baseline-check/summary.csv
```

The output directory must not exist. Runs and summaries refuse to overwrite
old output. A process lock excludes other instances of this runner, including
other checkouts. Stop other timing suites yourself; they do not use this lock.
Results contain a manifest of exactly the selected inputs, runtime/VM arguments,
cache capacities, worker counts, raw samples and separately generated summaries.
Record whether the revision has local changes in the label/report: the runner
does not infer a clean checkout from a supplied revision string.

Properties:

| Property | Default / meaning |
| --- | --- |
| `baseline.sizes` | `480x270`; comma-separated render dimensions, 65..4096 per axis before resize |
| `baseline.fixtures` | `all`; comma-separated exact fixture IDs, unknown IDs fail |
| `baseline.warmups` / `baseline.runs` | `1` / `3`; cold sequence is additional |
| `baseline.output` | New `target/baseline-<timestamp>` directory |
| `baseline.label` / `baseline.revision` | `current` / `unspecified`; set both for saved runs |
| `baseline.manifestOnly` | `false`; emit selected exact inputs without rendering |
| `fractal.aa.profile` | `false`; AA instrumentation changes the run's interpretation to attribution |

Generate the canonical manifest for review without measuring:

```shell
mvn -q -Pbaseline-benchmark -DskipTests verify \
  -Dbaseline.manifestOnly=true \
  -Dbaseline.sizes=480x270,1512x982,3024x1964 \
  -Dbaseline.output=target/baseline-manifest-review
```

For timing decisions, build before measurement and launch fresh JVMs directly,
avoiding Maven's build/JIT/allocation activity in the measured process:

```shell
mvn -q -DskipTests test-compile dependency:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile=target/baseline-classpath.txt
java -Dbaseline.output=target/baseline-direct \
  -Dbaseline.revision="$(git rev-parse HEAD)" -Dbaseline.label=current \
  -Dbaseline.sizes=1512x982,3024x1964 \
  -Dbaseline.fixtures=mandelbrot-overview,julia-overview,seahorse-adaptive \
  -Dbaseline.warmups=3 -Dbaseline.runs=10 \
  -cp "target/test-classes:target/classes:$(cat target/baseline-classpath.txt)" \
  com.shangin.fractal.render.BaselineBenchmark
```

The classpath separator above is for macOS/Linux; use `;` on Windows. These
JavaFX dependencies are loaded only as classes needed by the CPU pipeline;
this runner does not start the JavaFX toolkit or open a window.

## Timer and cache boundaries

All intervals use monotonic `System.nanoTime`. `-1` means **unmeasured/not
applicable**, never zero. Summary p95 uses nearest rank, and only `phase=sample`
rows enter medians/tails. Cold and warmup rows remain in the raw CSV.

| Field | Start → end |
| --- | --- |
| `plan_ms` | Step start → sample storage allocation and reuse planning/copy complete |
| `first_useful_samples_ms` | Step start → reuse plan ready when there is overlap, otherwise first completed backend region |
| `first_new_region_ms` | Step start → first newly calculated complete region; may be absent for a fully reused frame |
| `backend_ms` | Selected backend invocation → return, including production reference preparation, scheduling and worker drain; excludes frame creation/reuse and colorization |
| `returned_argb_ms` | Step start → complete newly allocated base ARGB array, including reuse, calculation, coloring preparation and colorization |
| `color_ms` | Coloring strategy preparation / array allocation → base ARGB ready; histogram preparation is included |
| `aa_prepare_ms` | Untimed-for-comparison first refinement used only to prepare the retained-AA case, reported separately |
| `aa_ms` / `first_aa_tile_ms` | Refinement call → success / first tile copied into the result array; includes production AA coordinator, workers, caches and callback copy |
| `operation_ms` | Entire step including AA and, for retained AA, its explicit preparation |
| `cancel_request_ms` | Step start → observed cancellation trigger |
| `cancel_tail_ms` | Observed trigger → backend return, after cooperative worker completion |

These scopes overlap; do not add `backend_ms`, `returned_argb_ms` and
`operation_ms`. In particular, a retained-AA operation includes its preparation
and is not an ordinary full-frame latency sample. The harness deliberately
colorizes the full base array after calculation, so `returned_argb_ms` is not
production progressive JavaFX publication. First useful **samples** are not
yet colored or displayed. Physical scanout and hardware/OS input latency remain
unmeasured. Use the separate [JavaFX render](GPU_RENDER_BENCHMARK_8_5_RESULTS.md)
and [interaction](INTERACTION_LATENCY.md) harnesses for those existing scopes;
they have not yet been migrated to this matrix.

`cold_fixture` is the first sequence on a new backend and AA service. It is
**not cold JVM startup**, because earlier fixtures/classes may already be warm.
Deep reference caches live for the fixture, including subsequent warmups and
samples. Base frames start empty for each sequence; only explicit navigation
steps consult active then retained frames through the production reuse planner.
AA starts empty for each new frame except the explicitly prepared same-frame
case. Base pan/resize tests do not claim retained-AA navigation coverage.
For process-cold measurements select one fixture per fresh JVM, with warmups
zero, and retain the first `cold_fixture` row separately from measured samples.

Cancelled work is never colorized or advertised as a returned full frame, even
if it happened to finish concurrently with the cancellation request. It can
publish other already-running regions before all workers see the flag. Partial
sample hashes and region counts are therefore scheduling-dependent. This
trigger measures post-first-region cancellation; cancellation during reference
construction, AA cancellation and UI generations remain separate profiling gaps.

## Correctness and performance gate

The CSV fingerprints include ready-sample indices, iterations, smooth values,
escape flags and orbit-trap distances, plus a separate ARGB hash. They detect
repeatability drift, but are not a substitute for sample-by-sample conformance
against a control build. Tests compare reused samples against a fresh render
on the identical inherited coordinate grid and compare every retained-AA ARGB
pixel against an empty-cache run. Rebuilding a fresh double grid can change
rounding, so it is not that reuse test's reference.

Before each optimization spike, declare its affected fixtures, timer scope,
quality contract and acceptable first-region/tail regression. For unchanged
quality, require the existing formula/deep/AA/reuse/cancellation regressions and
sample/color conformance. For timing decisions, use at least 30 matching
measured A/B pairs across at least three fresh process pairs, alternate build
order and keep hardware, power state, runtime, heap and worker configuration
fixed. Ten samples in each of three A/B process pairs meet the minimum count;
do not call two unrelated median aggregates paired observations. Retain raw
pair/order identities in the report. Small-fixture smoke timings are not this
gate and cannot justify production changes.

JFR/native attribution must use separate output directories and separate runs,
never run concurrently with timing pairs. For example, add
`-XX:StartFlightRecording=filename=target/baseline-attribution.jfr,settings=profile,dumponexit=true`
to a dedicated direct-JVM run; use a new recording filename and optionally
`-Dfractal.aa.profile=true`. Per-worker intervals overlap and are not additive
frame costs or process CPU utilization.

`heap_before/after_bytes` are used Java heap observations, not allocated bytes,
live retained size or RSS. GC deltas are MXBean counters around the step, with
`-1` when unavailable. Hashing and summary work occur outside these boundaries.
The harness does not measure task queue latency, allocation stacks, native
memory, power or thermal throttling. Collect hardware model/RAM, memory/GC
profiles and a sustained thermal experiment before a performance decision;
long-run repetition alone is not thermal evidence.

## Validation record

See [the initial validation report](BASELINE_VALIDATION.md). It is recorded
separately from historical performance reports and makes no speedup claim.
