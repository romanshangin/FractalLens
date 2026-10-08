# CPU antialiasing cache optimization

> Historical evidence: results and “current source” below refer to the dated
> experiment, not a new validation of this checkout. Only the
> [selected evidence](README.md) is included; entries marked “private archive”
> are unavailable in this repository. Original SHA values and recorded commands
> describe the original run and may require private revisions or inputs. See
> [provenance and replay limits](../docs/development/PUBLICATION.md).

Measurement dates: 2026-09-05–06. Baseline rendering code: `6c263f6`.
Machine: Apple M3 Pro, 12 logical processors, macOS 26.6.2,
Java 26.0.1, JavaFX 26.0.2, 2 GiB JVM heap, 11 AA workers.

## Cause and implementation

Every traversed pixel previously called the synchronized, access-order
`AntialiasSampleCache.color`, including millions of misses on a fresh frame.
For each AA candidate, encoding/insertion and a second lookup/color operation
also ran under that shared cache monitor. The smooth-coloring path first
colored the entire base image, then overwrote it with another complete pass
using quantized base phases.

The service now captures a read-only cache snapshot once, prepares retained
colors and a membership mask, and performs worker lookups without acquiring
the cache monitor. Fresh frames use an empty snapshot and allocate no retained
color array. Retained AA colors remain separate from the base-color image so
candidate detection still sees precisely the same base pixels.

Each worker stages at most one 32x32 tile. It encodes each candidate's phases
once and colors directly from the staged value; completed tiles enter the
cache in one merge. Sample arrays are reused within a tile, while each cached
phase payload owns its copy. Smooth base colors are prepared only once.
Sample positions, candidate thresholds, 4x4/direct and 2x2–4x4/deep sampling,
iteration budgets, smooth phases and linear-light averaging remain unchanged.

Tile admission is serialized with frame replacement, cache shifting and
cancellation. A cancelled worker cannot write its staged samples into the next
frame's cache. Changing the sampling pattern invalidates old AA samples;
changing the phase color scale excludes incompatible snapshots. Palette or
offset changes at the same phase scale can reuse the samples.

The cache retains its existing payload budget and pixel eviction mechanism.
Snapshot reads do not update individual LRU positions; admissions still evict
the oldest map entries, with tile completion determining insertion order.
A retained-color snapshot adds at most one ARGB array plus a membership bitset
(about 23.4 MiB at 3024x1964) when cached samples exist, in addition to
the existing snapshot index/reference arrays. A running snapshot can also keep
evicted phase payloads alive until refinement finishes. Worker staging is bounded
by the worker count and 1024 pixels per active tile. This is not a claim that
the cache's payload counter includes map/object overhead or measures process RSS.
If even one candidate cannot fit in the configured cache, its original uncached
coloring path is retained.

## Contention evidence

Separate JFR recordings cover nine Retina overview frames for each build.
The baseline recorded 3,266,546 contended cache-monitor entries: 3,076,006 from
`color` and 190,540 from `put`. Their accumulated event duration was 81,125 ms
across worker threads. The optimized recording has no contended entries on
that monitor; the service monitor used for tile admission/startup records 368
events totaling 17.926 ms.

These durations overlap across threads and include cold/warmup frames.
Low-threshold JFR recording substantially increases baseline runtime; the
performance tables use separate uninstrumented runs. Contention on
`ValidityMask` also appears during untimed base rendering and is not attributed
to this AA change. See saved diagnostic counters (private archive: `CPU_AA_CONTENTION_PROFILE.csv`).

`InteractiveAntialiasService.Profile` now exposes cache snapshot preparation,
tile-merge elapsed time and merge count separately. Worker stage timings are
elapsed intervals, not process CPU time, and must not be added to frame time.

## AA service comparison

The headless benchmark invokes the production AA service and copies each
published tile into a result image. Base rendering, fixture comparison and
CSV writing are outside the timed interval. This measures AA completion and
first AA tile; it excludes JavaFX publication and display scanout.

Three fresh JVM pairs alternate build order. Each process runs one cold
observation, three warmups and ten measured samples per scene/size: 30 measured
samples per build per case. Every returned pixel is compared against the
saved baseline image outside timing. Primary data are in
CPU_AA_BENCHMARK_RESULTS.csv (private archive: `CPU_AA_BENCHMARK_RESULTS.csv`).

| Scene | Size | Median before → after, ms | Speedup | p95 before → after, ms | First AA tile median before → after, ms |
| --- | --- | ---: | ---: | ---: | ---: |
| overview | 1512x982 | 231.86 → 40.75 | 5.69x | 235.30 → 44.30 | 4.11 → 1.81 |
| seahorse | 1512x982 | 444.13 → 216.66 | 2.05x | 457.49 → 228.83 | 5.84 → 1.89 |
| julia | 1512x982 | 427.11 → 191.23 | 2.23x | 446.82 → 195.10 | 11.91 → 5.39 |
| overview | 3024x1964 | 901.60 → 139.59 | 6.46x | 910.07 → 145.46 | 11.91 → 5.56 |
| seahorse | 3024x1964 | 1651.96 → 786.52 | 2.10x | 1684.90 → 811.78 | 14.92 → 4.40 |
| julia | 3024x1964 | 1630.40 → 743.75 | 2.19x | 1653.84 → 770.24 | 24.52 → 10.09 |

p95 uses the nearest-rank definition. The 30 observations per build are
matched by case, process-pair and iteration; build order alternates between
process pairs, not between individual frames. Candidate/sample counters are
zero placeholders when profiling is disabled, not evidence of absent AA.

Exploratory runs and JFR runs are excluded from this table. In particular,
the first optimized exploratory run overlapped a heavy JFR-file analysis;
the entire exploratory pair was excluded and the three confirmation pairs
were run without concurrent profiling, builds or tests.

## JavaFX publication comparison

The existing production-service/JavaFX benchmark has an explicit CPU-only mode
for this comparison. The same measurement driver is used with each build;
normal paired CPU/GPU mode still requires real GPU execution. CPU-only rows
do not establish GPU conformance.

Three fresh JVM pairs use the same cold/warmup/measured counts as the AA
service comparison: 30 measured frames per build per case. Total time includes
base calculation, coloring, AA and JavaFX publication. Raw data are in
CPU_AA_FX_BENCHMARK_RESULTS.csv (private archive: `CPU_AA_FX_BENCHMARK_RESULTS.csv`).

| Mode | Size | Total median before → after, ms | Speedup | Total p95 before → after, ms | First publication median before → after, ms |
| --- | --- | ---: | ---: | ---: | ---: |
| overview-aa | 1512x982 | 314.45 → 88.02 | 3.57x | 331.26 → 96.03 | 2.01 → 2.36 |
| overview-refined | 1512x982 | 316.73 → 79.30 | 3.99x | 325.71 → 163.64 | 25.23 → 22.34 |
| overview-aa | 3024x1964 | 1378.67 → 332.33 | 4.15x | 1452.58 → 409.83 | 9.45 → 10.20 |
| overview-refined | 3024x1964 | 1393.72 → 283.65 | 4.91x | 1543.52 → 325.57 | 95.12 → 79.76 |

Refined first-publication medians improve. Fast base-publication medians
increase by 0.35 ms at 1512x982 and 0.75 ms at Retina, before AA starts;
the measured total-frame improvement does not imply faster base calculation.
The ordinary mode publishes the base image first, while Refined waits for useful refinement;
their first-publication values have different meanings. AA numerical checks
come from the headless pixel comparisons and regression suite, not the
CPU-only driver’s `max_smooth_error=0` placeholder.

| Mode | Size | Median GC time per frame before → after, ms | Median heap after frame before → after, MiB |
| --- | --- | ---: | ---: |
| overview-aa | 1512x982 | 2.0 → 2.0 | 257.6 → 305.3 |
| overview-refined | 1512x982 | 2.0 → 1.0 | 264.7 → 351.4 |
| overview-aa | 3024x1964 | 9.5 → 6.0 | 418.1 → 833.3 |
| overview-refined | 3024x1964 | 10.5 → 5.0 | 717.6 → 1006.6 |

Heap observations include live objects and garbage awaiting collection; they
are not retained-cache size, allocation rate or RSS. GC/memory columns remain
in the raw data. These bounded runs do not establish long-duration thermal
behavior or memory usage during repeated cancellation.

These measurements end at PixelBuffer publication. macOS emitted CVDisplayLink
warnings during the test-window run; actual display scanout/vsync latency is
not measured. The sandbox could not expose a screen, so these checks require
graphical access. They do not change the CPU/GPU continuation decision.

## Correctness and remaining work

New regressions cover worker-local phase ownership, atomic tile admission,
memory limits, immutable snapshot masks, cancelled work contaminating a
replacement frame, palette reuse, and sampling-pattern/color-scale invalidation.
Existing tests retain deep-AA control samples, cancellation, queued callbacks,
recolor equivalence and pan/resize retention coverage.

Supplementary controls use one process per build, one cold frame, two warmups
and five measured frames; each complete image matches its baseline pixel for
pixel. Data: CPU_AA_CONTROL_RESULTS.csv (private archive: `CPU_AA_CONTROL_RESULTS.csv`).

| Control | Scene / size | AA median before → after, ms | First tile median before → after, ms |
| --- | --- | ---: | ---: |
| deep | deep / 480x270 | 429.55 → 439.81 | 15.61 → 18.71 |
| disabled-cache | overview / 1512x982 | 253.78 → 53.44 | 6.37 → 2.54 |
| exterior | exterior / 1512x982 | 193.59 → 18.79 | 6.97 → 3.49 |
| exterior | exterior / 3024x1964 | 775.86 → 62.82 | 21.55 → 7.24 |
| jitter | julia / 1512x982 | 585.06 → 239.63 | 16.22 → 6.71 |
| jitter | overview / 1512x982 | 288.36 → 53.83 | 5.33 → 2.84 |
| resume | julia / 1512x982 | 279.14 → 27.07 | 6.77 → 11.75 |
| resume | overview / 1512x982 | 269.16 → 24.88 | 4.90 → 3.54 |

These five-sample controls suggested little benefit in the deep fixture and
higher first-tile latency for retained Julia. The longer follow-up below
supersedes these two preliminary timing observations.

The follow-up has three fresh JVM pairs in alternating build order, with
30 measured frames per build/control and full pixel equality throughout.
Data: CPU_AA_FOLLOWUP_RESULTS.csv (private archive: `CPU_AA_FOLLOWUP_RESULTS.csv`).

| Control | AA median before → after, ms | AA p95 before → after, ms | First tile median before → after, ms |
| --- | ---: | ---: | ---: |
| deep | 417.44 → 414.17 | 431.97 → 441.20 | 15.25 → 14.94 |
| resume-julia | 280.18 → 25.91 | 294.04 → 34.28 | 6.38 → 11.10 |

Deep-AA time is effectively unchanged in this fixture; the optimization is
not a demonstrated deep-zoom speedup. Retained Julia finishes about ten times
faster, with a first-tile tradeoff of about 5 ms: retained colors are prepared
before tile dispatch. This is a measured limitation of the snapshot approach,
recorded as follow-up work in roadmap 9.2. The fresh-frame JavaFX gains do not
erase that limitation.

Validation: the complete default Maven suite passes: **353 tests discovered,
333 executed, 20 opt-in cases skipped; no failures or errors**. The two AA test
classes contain 21 passing tests, including five new regressions.

The opt-in JavaFX integration suite also passes: **16 reported, 14 executed,
2 fullscreen cases skipped**. This covers resize/pan retention, Refined AA
publication and cancellation interactions. The new `aa-benchmark` Maven profile
was smoke-tested through `verify` with a 64x48 fixture. Native GPU conformance
was not re-run for this CPU-only change; existing CPU/mock palette and GPU tests
are included in the default suite.

Derivative-orbit reuse, new candidate detectors and changing AA sample counts
remain separate experiments. This change removes measured cache contention and
duplicate preparation without changing the requested sampling quality.

## Reproduce

```sh
mvn test
MAVEN_OPTS='-Xmx2g' mvn -Paa-benchmark verify -DskipTests \
  -DaaBenchmark.warmups=3 -DaaBenchmark.runs=30 \
  -DaaBenchmark.output=target/aa-current.csv

# Separate stage diagnostics; never use these rows as the primary timing gate.
MAVEN_OPTS='-Xmx2g' mvn -Paa-benchmark verify -DskipTests -Dfractal.aa.profile=true \
  -DaaBenchmark.scenes=overview -DaaBenchmark.sizes=3024x1964 \
  -DaaBenchmark.output=target/aa-current-profile.csv

# Real JavaFX publication, CPU only.
JAVA_TOOL_OPTIONS='-Xmx2g -Djavafx.cachedir=/tmp/fractallens-javafx-cache -Dfractal.gpu.enabled=false -Dfractal.benchmark.cpuOnly=true -Dfractal.benchmark.scenes=overview-aa,overview-refined -Dfractal.benchmark.output=target/aa-fx-current.csv' mvn -Pgpu-render-benchmark javafx:run
```

The headless profile also accepts `aaBenchmark.pattern=DETERMINISTIC_JITTER`,
`aaBenchmark.resume=true`, `aaBenchmark.scenes=deep` and
`fractal.aaCache.maxBytes`. The deep diagnostic uses 2488 iterations at scale
`1.6e-13`, centered at
`(-0.8317528516858322713653476366999, 0.207813754242134522471317257011028)`;
the ordinary scenes use a fixed 300-iteration cap. Run deep controls
at `aaBenchmark.sizes=480x270` initially.

For a before/after comparison, preserve each build's compiled classes and run
the same benchmark driver and dependencies in separate fresh JVMs with `-Xmx2g`.
The baseline renderer is the parent commit; only the measurement driver is new.
Use `aaBenchmark.reference=<directory>` and `aaBenchmark.writeReference=true`
on the baseline to save complete ARGB fixtures. Run the other build with the
same reference directory and without `writeReference` to compare every pixel.
Alternate build order, retain cold/warmup rows, and confirm in JavaFX separately.
