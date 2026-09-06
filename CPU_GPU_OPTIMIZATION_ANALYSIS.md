# CPU/GPU optimization analysis

Analysis date: 2026-09-05. Repository baseline: `6c263f6`.

CPU AA follow-up (2026-09-05–06): the cache contention and repeated preparation
identified here have now been measured and optimized. See
[implementation, paired measurements and regression results](CPU_AA_OPTIMIZATION_RESULTS.md).
Code descriptions and historical timings below refer to baseline `6c263f6`.

## Decision and scope

Keep CPU calculation as the default. Prioritize CPU antialiasing, then extend
the successful deep-zoom algorithms. The most useful new GPU algorithm experiment
is a CPU high-precision reference with GPU perturbation/BLA, rather than another
direct FP32 certificate or a repeat of the rejected double-single recovery loop.
Hardware-native FP64 and selective use of the resident backend remain separate
conditional opportunities.

The integrated GPU calculation path currently accepts only certified Mandelbrot
jobs without orbit traps, with 1–1000 iterations and a supported coordinate
grid. Deep zoom and unsupported work use CPU. A future GPU perturbation path
must establish its own higher-iteration contract; the existing FP32 gate does
not cover it. See [runtime modes](GPU_RUNTIME.md#platform-and-numeric-requirements)
and [backend eligibility](src/main/java/com/shangin/fractal/gpu/GpuMandelbrotRenderBackend.java).

This analysis covers the relevant families of optimization: avoiding work,
reducing iteration work, numerical representations, SIMD and scheduling,
allocation and synchronization, AA/coloring, transfers and presentation,
backend selection, platform APIs, and export. It distinguishes measured results
from proposed experiments; it cannot establish an exhaustive list of every
possible algorithm or guarantee a speedup for an unimplemented option.

The saved reports, raw GPU CSVs and current implementation were inspected.
Retina medians used below were recomputed from `phase=sample` rows. No new
performance run was performed for the initial analysis; the CPU AA follow-up
linked above contains subsequent measurements. Existing CPU
experiments have smaller sample counts and older runtimes than the recent GPU
gates; their outcomes are evidence about those implementations and workloads,
not universal hardware limits.

The executable follow-up is [roadmap section 9](ROADMAP.md#9-pursue-cpugpu-optimization-from-the-measured-bottlenecks).
Existing sections 8.7 and 8.8 retain the separate Windows and Intel/AMD Mac work.

## What has already been tested

| Area | Recorded result | Consequence |
| --- | --- | --- |
| Exact pan reuse | Tile-32 total speedup 12.31–13.96x with 95% overlap; 5.28–5.68x with 75% overlap. These are backend measurements, excluding JavaFX. | Preserve reuse; measure interaction and publication before claiming UI gains. [Frame reuse results](BENCHMARK_RESULTS.md). |
| CPU cardioid/bulb shortcuts | Mandelbrot overview 3.68x and 100x 4.12x faster in the single-thread experiment. | Already implemented; protect exact boundary behavior. [Formula results](FORMULA_BENCHMARK_RESULTS.md#main-cardioid-and-period-2-bulb-rejection). |
| CPU conjugate symmetry | 1.72–1.88x parallel-render speedup for eligible empty, real-axis-centered Mandelbrot grids. | Already implemented; not applicable to arbitrary pans, formulas or traps. [Symmetry results](FORMULA_BENCHMARK_RESULTS.md#mandelbrot-conjugate-symmetry-optimization). |
| Exact periodicity / manual square caching | Periodicity regressed Mandelbrot 100x about 2.8%; square caching regressed Julia about 4–5%. | Do not repeat unchanged hot-loop additions. [Formula results](FORMULA_BENCHMARK_RESULTS.md). |
| Sample API / specialized Julia | Escape analysis removes most formula-result allocation; a direct Julia pipeline differed only about 1%. | Retain the generic scalar API. This does not prove that AA arrays, escaping objects or cache locks are cheap. [Allocation results](FORMULA_BENCHMARK_RESULTS.md#fractalsample-allocation-cost-experiment). |
| Java Vector API | Two FP64 lanes on the tested ARM runtime: overview 0.55x scalar/vector speedup, 100x 0.99x, 10000x 1.08x. | Reject this masked implementation on M3 Pro; wider species and different lane scheduling are separate experiments. [Vector results](FORMULA_BENCHMARK_RESULTS.md#java-vector-api-experiment). |
| CPU tiles/workers | 64-pixel tiles help overview but delay first deep regions; all 12 workers add only about 3–5% deep throughput over 11. | Keep 32 and CPU-minus-one defaults until workload-aware scheduling wins both latency and throughput. [Tuning results](FORMULA_BENCHMARK_RESULTS.md#tile-size-and-worker-count-retuning). |
| Naive double-only perturbation | About 1.8x slower, with iteration mismatches. | Do not move ordinary views to a delta recurrence without a different amortization and precision argument. [Early experiment](FORMULA_BENCHMARK_RESULTS.md#perturbation-and-reference-orbit-investigation). |
| Deep modified rebasing | Cached 480x270 glitch workload: 515.89 → 175.14 ms; additional references eliminated in that case. | Successful production optimization; preserve it in future CPU/GPU algorithms. [Rebasing results](FORMULA_BENCHMARK_RESULTS.md#modified-perturbation-rebasing). |
| Conservative CPU BLA | At scale `1e-30`, cached 180.83 → 57.33 ms (3.15x); 1,755 scalar steps become one 1,753-step block plus two scalar steps. No useful blocks at the shallower glitch workload. | Extend coverage and lower setup/storage cost; do not extrapolate one uniform-escape view to all deep scenes. [BLA results](FORMULA_BENCHMARK_RESULTS.md#conservative-bla-blocks). |
| Cubic series / looser BLA radius | Safe series skips gained only 1–3%; longer skips violated smooth tolerance. BLA `2^-32` failed a boundary control; production uses `2^-52`. | Higher-order or bounded-error approaches need a new argument and gate; loosening tolerances is not an optimization. [Roadmap 7.6](ROADMAP.md#76-x-evaluate-secondary-deep-zoom-optimizations), [BLA results](FORMULA_BENCHMARK_RESULTS.md#conservative-bla-blocks). |
| GPU palette residency | Large-AA recoloring through the JavaFX buffer: 5.633 ms CPU vs 4.717 ms GPU; three other workloads favor CPU. First GPU use costs 147.5 ms in the longer run. | A possible narrow warmed-use policy, not a global GPU default. [Palette results](PALETTE_BENCHMARK_RESULTS.md). |
| Integrated GPU 8.4 → 8.5 | Host recovery/conversion parallelization, single smooth conversion, certified interior shortcuts and 192x192 batches substantially improved GPU time. All ten repeated CPU/GPU cases still lose. | Those optimizations are already done. Work on a different bottleneck. [8.4](GPU_RENDER_BENCHMARK_RESULTS.md), [8.5](GPU_RENDER_BENCHMARK_8_5_RESULTS.md). |
| GPU candidate-detection proposal | Even subtracting the complete measured candidate critical path leaves the hybrid AA pipeline 1.03–1.07x slower. | Moving detection alone cannot justify expansion of the current hybrid backend. [8.6 decision](GPU_RESIDENCY_8_6_DECISION.md). |
| Whole-frame resident GPU | Retina overview/exterior win before JavaFX; seahorse loses. Small overview is essentially tied. | Residency is useful for some workloads, but this spike failed its predeclared 15% cross-scene gate. [Resident results](GPU_RESIDENT_SPIKE_RESULTS.md). |
| Centered-error FP32 second pass | Zero additional certifications; Retina seahorse calculation 27.06 → 46.65 ms. | Removed; another FP32 interval shape is unsupported by this evidence. [Second-stage results](GPU_RESIDENT_SECOND_STAGE_PROFILE.csv). |
| Double-single GPU recovery | Even without a rigorous certificate, Retina seahorse is 37.3% slower and has 54 color outliers, maximum channel error 189. | Removed; adding a certificate to this same implementation cannot rescue its measured optimistic ceiling. This does not rule out every use of multiword arithmetic. [Double-single results](GPU_RESIDENT_DOUBLE_SINGLE_RESULTS.csv). |

### Keep the measurement boundaries separate

The current production-style 8.5 gate includes calculation, CPU recovery, JavaFX
buffer publication and, where selected, CPU AA. Retina medians in milliseconds:

| Workload | CPU | Integrated GPU | GPU / CPU |
| --- | ---: | ---: | ---: |
| Overview base | 71.93 | 238.67 | 3.32x |
| Exterior base | 69.94 | 165.06 | 2.36x |
| Seahorse base | 164.12 | 363.00 | 2.21x |
| Overview Fast + AA | 1345.79 | 1526.22 | 1.13x |
| Overview Refined + AA | 1378.67 | 1534.89 | 1.11x |

Sources: [primary CSV](GPU_RENDER_BENCHMARK_8_5_RESULTS.csv) and
[Refined CSV](GPU_RENDER_BENCHMARK_8_5_REFINED.csv). Refined first publication is
also worse on GPU: 244.50 vs 92.26 ms. Neither timer measures physical scanout.

The resident benchmark instead ends at a returned ARGB array. It uses fixed 300
iterations and ICE coloring, with no JavaFX publication, AA, pan reuse or input
latency. Its conformance comparison checks colors, not the full sample plane.
These limits follow directly from
[`GpuResidentRenderBenchmark`](src/main/java/com/shangin/fractal/ui/GpuResidentRenderBenchmark.java)
and its [native test](src/test/java/com/shangin/fractal/gpu/GpuResidentMandelbrotNativeTest.java).

| Retina workload | CPU to ARGB | Resident GPU to ARGB | Recovery fraction | Decision |
| --- | ---: | ---: | ---: | --- |
| Overview | 44.590 ms | 26.813 ms | 8.20% | 39.9% lower elapsed time in this benchmark |
| Exterior | 49.551 ms | 7.976 ms | 0.11% | 83.9% lower elapsed time in this benchmark |
| Seahorse | 125.887 ms | 156.173 ms | 68.53% | 24.1% higher elapsed time |

Source: [resident CSV](GPU_RESIDENT_SPIKE_RESULTS.csv). There are 5,939,136 pixels
at 3024x1964; 4,070,274 seahorse corrections cost 117.013 ms. The resident and
integrated certificates have different acceptance rates; do not mix the
resident overview's 91.80% acceptance with the integrated backend's 95.12%.

Cross-run CPU medians differ substantially. In particular, subtracting the
resident 26.813 ms from the integrated 71.93 ms would compare different scopes
and would not establish an application speedup.

## Most actionable bottlenecks in the current code

### CPU antialiasing: measure synchronization before rewriting arithmetic

The [8.6 profile CSV](GPU_RESIDENCY_8_6_PROFILE.csv) reports CPU Retina AA wall
time around 1,089 ms, with only 164,354 candidates (2.77% of pixels) and
2,629,664 subpixel samples. Candidate detection's busiest-worker elapsed time
is about 72–73 ms. The summed sampling and cache/color intervals are worker
elapsed times measured using `System.nanoTime`, not process CPU utilization;
they overlap and must not be added to or subtracted from frame time as if
they were sequential stages.

[`InteractiveAntialiasService.refineTile`](src/main/java/com/shangin/fractal/export/InteractiveAntialiasService.java)
calls `sampleCache.color(...)` before candidate timing for every traversed
pixel. [`AntialiasSampleCache`](src/main/java/com/shangin/fractal/render/AntialiasSampleCache.java)
uses a synchronized access-order `LinkedHashMap`; insertion and the subsequent
color lookup also acquire that monitor. The initial lookup and its contention
are outside `aa_cache_color_cpu_ms`. Millions of cache misses can therefore
serialize workers without appearing in the reported cache/color interval.
This is a code-supported hypothesis, not a newly measured root cause.

First capture monitor/parking, allocation and worker-idle profiles alongside
AA wall time. Compare an immutable lookup snapshot for retained samples,
tile-local primitive sample staging, one cache merge per completed tile and
coloring directly from that staging. Preserve bounded memory, cancellation,
palette phases, pan/resize retention and the exact existing sampling pattern.
The old scalar `FractalSample` escape-analysis experiment does not cover
`FractalSample[16]` objects passed to the AA cache.

Candidate detection also reruns a derivative orbit for non-edge pixels in
[`AdaptivePngExportService`](src/main/java/com/shangin/fractal/export/AdaptivePngExportService.java).
Compare retaining a distance/candidate sidecar during an AA-enabled base pass
with this second pass. Account for its known 1.4–2x base arithmetic cost and
possible delay to first publication. Keep the image-space detector: exterior
distance estimates alone miss interior-centered boundary pixels.

### Deep zoom: successful skipping changes the next bottleneck

The current [BLA table](src/main/java/com/shangin/fractal/render/MandelbrotBlaTable.java)
uses objects per block, a generation-wide delta bound and a strict radius. It
does not accelerate the scaled-exponent path or retries against additional
references. The [perturbation backend](src/main/java/com/shangin/fractal/render/MandelbrotPerturbationRenderBackend.java)
caches exact-match primary orbits but keeps additional references and BLA
tables local to a generation.

Compare primitive block arrays, tile-local bounds, sampler/table sharing with
explicit ownership, and bounded reuse of compatible references. Extend scaled
rebasing/BLA only with exponent-preserving arithmetic. A reference that remains
useful after pan need not equal the new center, but its precision, coverage,
glitch behavior and iteration capacity must still be checked. Never reuse a
viewport-specific radius without recomputing its bound.

Higher-order series, polynomial block approximations and adaptive error bounds
are research options for scenes where strict BLA accepts no useful skips.
Begin with an offline correctness/skip-coverage experiment. The existing cubic
series and loose-radius failures make another unchecked threshold sweep a poor
next step.

### GPU: correction cost is numerical, not just transfer overhead

The resident rejection profile assigns 51.16% of seahorse rejections to uncertain
escape and 48.84% to an excessive smooth interval. All other categories are
zero. Dispatch, palette-phase tuning or list compaction may reduce overhead,
but they do not fix these rejected orbits.

A useful new experiment must reduce the actual work: share an accurate CPU
reference, skip many iterations on GPU, or use hardware with efficient native
FP64. Another possibility is to avoid dispatching costly scenes at all. An
early CPU fallback protects latency; it does not make GPU calculation faster
on that scene or pass the existing cross-scene GPU gate.

### Presentation: optimize it as its own measured stage

[`SurfaceBuffer`](src/main/java/com/shangin/fractal/ui/SurfaceBuffer.java) owns a
heap `IntBuffer`, copies complete arrays in `publish`, and reports the entire
buffer dirty via `updateBuffer(... -> null)`. Compare bounded dirty rectangles,
callback coalescing after the first ready region, reusable output buffers and
avoided base recoloring before designing native texture interop. Validate
retained refined pixels and resize behavior at every publication boundary.

JavaFX 26's public `PixelBuffer` API accepts `IntBuffer`/`ByteBuffer` and a dirty
rectangle; it does not expose an external Vulkan/Metal texture import. A direct
buffer may remove a host copy, but it does not by itself prove GPU-to-display
zero-copy. [JavaFX PixelBuffer API](https://docs.oracle.com/en/java/java-components/javafx/26/docs/javafx.graphics/javafx/scene/image/PixelBuffer.html).

## Options and when to pursue them

Priorities: **P1** next practical experiments after baseline profiling;
**P2** conditional algorithm or hardware work; **P3** research or a separate
product mode. Effort is relative to this repository, not a calendar estimate.

| Option | Scope / expected mechanism | Priority, effort and stop condition |
| --- | --- | --- |
| Batched AA cache and primitive sampling | All CPU formulas; remove per-pixel shared-map traffic and transient sample arrays. | P1, medium. Profile first; stop if lock/allocation reduction does not improve full AA time. |
| AA distance/candidate reuse | Analytic formulas; reuse derivative information instead of rerunning whole orbits. | P1, medium. Reject first-publication regressions or lost edge candidates. |
| Nested adaptive subpixel sampling | Reuse early samples and stop refinement in low-error pixels. Current direct 4x4 and deep 2x2/4x4 grids are not automatically nested. | P2, medium. Changing positions or counts is a quality-policy experiment, requiring dense-reference image tests and deterministic cache keys. |
| Scalar instruction-level parallelism | Interleave 2/4/8 independent pixel orbits, modest loop unrolling, defer smooth logs to escaped samples only where the current path repeats work. | P1/P2, medium. Inspect JIT output; stop at register-pressure, cancellation or overview regressions. No assumed FMA equivalence. |
| SIMD with lane refill/compaction | Batch long-lived orbits; measure lane occupancy, especially on wider x86 FP64 species and AA samples. | P2, medium/high. Keep scalar dispatch for losing workloads. Repeating the old two-lane masked loop is closed. |
| Adaptive tiles and shared worker budget | Small first tiles, larger homogeneous tiles, cost-based ordering, prevent base/AA/recovery pools from oversubscribing CPU. | P1, medium. Keep UI headroom and bounded cancellation; do not hard-code Apple core affinity without evidence. |
| Reference/BLA layout and reuse | Reduce preparation, lookup and memory traffic; tile-local coverage, compatible reference reuse, BLA for retries. | P1, medium/high. Revalidate all bounds, precision and cache ownership. |
| Iteration continuation | At unchanged coordinates, retain recurrence state for unfinished pixels and resume when the requested cap increases. | P2, medium/high. Include state memory, derivative/trap state and invalidation; existing cap-limited samples alone cannot be reused as completed higher-cap results. |
| Scaled-exponent rebasing/BLA | Extreme zoom where standard double deltas underflow. | P2, high. Preserve exponent range and scalar/high-precision fallback; measure real extreme scenes. |
| Stronger series/polynomial approximation | Skip work where current BLA is too conservative. | P2/P3, high. First demonstrate more valid skips within the existing error contract. |
| Arbitrary-precision CPU engine | Compare optimized BigDecimal setup with binary fixed-point, double-double transition precision, or MPFR/GMP behind a batched FFM boundary. | P2, high. Only when reference construction or exact fallback dominates; keep portable BigDecimal and compare numerical semantics. |
| Certified interior/region tests | Dynamically prove attracting cycles, interval-enclosed tiles or conservative disks before skipping iteration. | P2/P3, high. Exact checkpoint periodicity already lost; derivative thresholds and equal-border fills alone are insufficient. |
| Formula-specific extensions | Julia and Multibrot batching first; separate perturbation designs for Julia, Burning Ship and Tricorn. | P2/P3, high. New correctness controls per formula; Mandelbrot symmetry, interior predicates and derivatives do not transfer automatically. |
| GPU perturbation plus BLA | CPU builds accurate reference/blocks, GPU evaluates local deltas and compact failures. Can amortize precision and reduce iteration count. | P2, high, preferred new GPU algorithm spike. Require native sample conformance and a measured ceiling before production wiring. |
| Native GPU FP64 | Direct or perturbation compute on supported devices, primarily a Windows experiment. | P2, medium/high plus hardware. Query and enable capability, measure throughput and compiler behavior; FP64 support alone is not speed or exact parity. |
| Multiword/fixed-point GPU arithmetic | Double-single, triple/quad-float, integer limbs, or emulated FP64 in selected blocks/tails. | P3, high. Existing full-rejection double-single route is closed; require a materially different workload or algorithm and a cheap ceiling first. |
| GPU work distribution | Tune workgroups, register use, tile grouping, rejection-list compaction and bounded persistent queues for an otherwise promising kernel. | P2, medium. Query subgroup support; account for queue/state traffic, divergence and watchdog/cancellation bounds. |
| Hybrid CPU/GPU partitioning | Assign independent tiles by measured cost; CPU handles unstable tiles, GPU stable tiles; use a common host-worker budget. | P2, high. Include classification overhead and Apple unified-memory/power contention. Avoid duplicate full-frame work. |
| Resident backend selection | Use cheap pilot regions and recent compatible statistics to predict frame cost and rejection density. | P2, medium/high. A selector requires its own predeclared gate; it cannot retroactively pass the failed all-scene GPU gate. |
| Fewer transfers/submissions | Axis packing, compact samples, persistent buffers, two bounded batches and overlap where stages permit it. | P2, medium. Much is already implemented; measure remaining pack/wait costs before extending. |
| Native texture presentation | A native surface or tested graphics interop could remove ARGB readback. | P3, very high. Requires measured readback/display dominance and macOS/Windows lifecycle design; not an API flag in JavaFX. |
| Selective GPU recoloring | Warm, large-AA palette animation with resident phase data. | P2, small/medium. Measure first use, duty cycle, invalidation and small-scene fallback; preserve existing color bounds. |
| GPU AA, histogram, traps and cache | Move expensive subpixel work and keep samples resident after a viable base algorithm exists. | P2, high, gated by 8.6. Detection alone already failed; exact fallback and every feature need their own contract. |
| Native CPU kernels / alternate JIT | Batched C/C++/Rust SIMD, compiler flags or another JVM could improve a measured hot loop. | P3, high. Measure conversion/call costs, packaging and actual end-to-end gain; rewriting the UI/language has no demonstrated calculation benefit. |
| Native Metal / other GPU APIs | Compare one equivalent kernel if profiling isolates MoltenVK translation/dispatch cost. Keep Vulkan as the portable contract. | P3, high. An API change cannot supply missing numerical precision; see platform discussion below. |
| Preview-only approximation | Lower resolution/iteration preview, temporal reprojection, fast image-space AA or uncertified FP32 can improve navigation latency. | P3, explicit display policy. Keep previews outside exact sample caches/export; always converge to the requested iterations and precision. |
| Off-screen tiled export / multiple devices | Large independent tiles or animation frames can amortize setup and distribute work. | P3, separate workload. Include seams, cancellation, memory, image encoding and transfers; no interactive-speed claim. |

### GPU perturbation experiment: what would make it different

Perturbation uses low-precision deltas around a high-precision reference; BLA
replaces several valid recurrence steps with a short linear map. Those are the
algorithmic foundations already used by the CPU backend, and are documented by
[Mathr](https://mathr.co.uk/web/deep-zoom.html). A GPU implementation is a proposed
application of these ideas, not an observed FractalUI GPU result.

Start with the existing scalar CPU reference/BLA data and fixed diagnostic
grids. Quantify GPU error from reference conversion, coefficient conversion,
delta updates, escape testing and smooth evaluation. FP32 BLA needs its own
error/radius policy; copying CPU `2^-52` bounds or increasing them to `2^-23`
does not establish correctness. Retain exact CPU recovery from original
coordinates and report failure density and recovery cost.

Test both the BLA-friendly `1e-30`/`1e-80` views and boundary views where BLA
cannot skip. Deep zoom may help amortization, but it may also leave only two
CPU scalar steps per pixel, making GPU dispatch relatively expensive. A
high-precision reference and a cheap-looking shader are not sufficient for a
performance decision.

For extreme zoom, normalized deltas plus a separate scale may reduce ordinary
iteration cost, with full-range operations around small reference values. This
is an algorithmic option described in
[Mathr's rescaling discussion](https://mathr.co.uk/blog/2021-05-14_deep_zoom_theory_and_practice.html#rescaling),
requiring a fresh native precision gate in this application. Keep dispatches
bounded in both elapsed time and recurrence steps.

### Numerical and spatial shortcuts: preserve the contract

Classifying a tile from matching corner or border colors (including naive
Mariani–Silver/boundary-tracing fills) can miss small structures and smooth
variation. Quadtree subdivision is useful as scheduling; filling a region
without evaluating its pixels needs a conservative numerical argument or must
remain an approximate preview. Similarly, reaching `maxIterations` means
bounded under this render contract, not a proof of mathematical membership.

Native FP64, FMA, fast-math, different logarithms, multiword arithmetic and MPFR
can change rounding and boundary iteration counts. Treat them as numerical
backend changes, not mechanically exact replacements. MPFR provides explicit
binary precision and rounding, which makes it a candidate reference engine;
decimal and binary operation sequences still need conformance comparisons.
[MPFR manual](https://www.mpfr.org/mpfr-current/mpfr.html#Introduction-to-MPFR).

### Hardware and API choices

The tested M3 Pro MoltenVK path has no usable shader FP64 mode; keep that
measurement local to this device/runtime. Vulkan defines `shaderFloat64` as
an explicitly enabled capability, not a throughput guarantee.
[Vulkan feature specification](https://docs.vulkan.org/refpages/latest/refpages/source/VkPhysicalDeviceFeatures.html).
Windows native Vulkan and Intel/AMD Mac validation remain mandatory separate
tracks; do not infer their results from Apple Silicon.

Java's JDK 26 Vector API is still an incubating API. Revisit it for a concrete
species or lane-management improvement, rather than relying on a future
stabilization date. [OpenJDK announcement](https://inside.java/2025/12/02/jep529-target-jdk26/).
GPU subgroup arithmetic/ballot features should also be queried at runtime;
their availability and widths are device-dependent.
[Vulkan subgroup guide](https://docs.vulkan.org/guide/latest/subgroups.html).

Native Metal is a possible macOS optimization experiment, while native Vulkan
retains Windows portability. CUDA would be an NVIDIA-only optional backend;
OpenCL/SYCL or a Java-to-GPU compiler such as TornadoVM would need a separately
verified device/runtime matrix and equivalent precision tests. WebGPU, OpenGL,
or a renderer/UI rewrite likewise require evidence of a specific bottleneck.
None has been benchmarked here as an equivalent replacement, so no migration
or support claim follows from listing them.

Apple shared memory allows both processors to access resources, but still
requires synchronization. It does not remove Java array copies, queue waits or
JavaFX upload costs. Native memory experiments must also cover discrete GPUs.
[Apple resource storage modes](https://developer.apple.com/documentation/metal/setting-resource-storage-modes).

CPU/GPU selection by measured hardware and number type has precedent in
[Fraktaler 3's documented calibration](https://fraktaler.mathr.co.uk/#wisdom).
That is an architectural reference, not evidence that its performance or
precision policy transfers to FractalUI.

## Validation and promotion rules

1. **Freeze comparable fixtures.** Record exact coordinates, precision, actual
   iteration limit, formula, coloring, AA pattern, dimensions, reuse state,
   commit, JVM, GPU/driver and power conditions. Include overview, exterior,
   seahorse, Julia, other supported formulas, traps/histogram, direct/deep
   transitions, glitch-heavy and BLA-friendly zooms, scaled-exponent scenes,
   95/75/25% pans, reverse-cache navigation and resize-then-drag.
2. **Separate five timing boundaries.** Formula kernel; complete backend;
   returned ARGB; JavaFX publication with base/AA completion; input-to-visible
   update. Physical scanout needs a separate measurement. Include cold start,
   steady state, first useful region, full frame and cancellation latency.
3. **Profile independently.** Use JFR/appropriate native profilers for locks,
   allocation, worker utilization, kernel occupancy and transfers. Extend AA
   instrumentation around initial cache lookup, scheduling and waits. Do not
   treat overlapping worker elapsed times as an additive critical path. GPU
   timestamp absence remains unavailable, not zero. Uninstrumented rows with
   zero component fields do not prove that recovery or transfers were free.
4. **Keep numerical gates.** Ordinary exact CPU paths retain exact sample
   equivalence where required. The integrated certified GPU gate retains exact
   escape/iteration and finite smooth error <= 0.01; deep CPU changes retain
   the existing `1e-6` smooth controls and BigDecimal reference checks. Test
   palette/AA colors separately. Resident color-only checks are insufficient
   before production sample storage, histogram, traps, reuse or export.
5. **Keep lifecycle and quality gates.** Cancellation, device loss, failed native
   loading, memory ceilings, no stale publications, retained AA and precise
   fallback remain mandatory. Pure pan/resize can preserve the iteration cap;
   any zoom-containing batch recalculates it. Preview pixels never become exact
   cached samples. Changing AA counts/patterns requires an explicit quality
   comparison, not merely matching a cheaper algorithm to itself.
6. **Predeclare each performance decision.** Preserve the resident spike's
   existing >=15% median win across all six cases as its unchanged gate. For a
   new CPU optimization, propose >=10% median improvement on its target slow
   workloads and <=5% regression on controls; confirm near-threshold results
   with longer runs. For a selective GPU policy, predeclare eligible workload
   classes, include pilot/fallback costs, require >=15% gain on selected cases
   and <=5% regression elsewhere, and cap first-publication/cancellation
   regressions. These are proposed new experiment criteria, not passed gates
   or a waiver for 8.6. Use alternating paired runs, at least 30 measured pairs
   for confirmation, multiple process starts, medians and tail distributions.
   Do not interpret p95 from three or five observations as a stable estimate.
7. **Promote only complete wins.** A promising isolated kernel remains a spike
   until production-service/JavaFX/AA checks pass. Record RSS, heap/native/cache
   budgets, GC, prolonged-navigation memory stability and sustained thermal/
   energy behavior separately. Keep per-platform fallback decisions and retain
   rejected prototypes' results to avoid repeating closed experiments.

## Existing reproduction entry points

Use these profiles for the corresponding scope; they are not one interchangeable
benchmark. Save new runs to distinct files instead of overwriting historical
evidence. Commands below are starting points, not a claim they ran in this
analysis. Full scene coverage and confirmation counts are future 9.1 work.

```sh
mvn test
mvn -Pformula-benchmark verify -DskipTests
mvn -Prender-tuning-benchmark verify -DskipTests
mvn -Pbenchmark verify -DskipTests
mvn -Pdistance-estimation-benchmark verify -DskipTests
mvn -Pperturbation-benchmark verify -DskipTests
mvn -Pperturbation-benchmark verify -DskipTests -Dperturbation.deepScale=1e-30 -Dperturbation.bla=false
mvn -Pperturbation-benchmark verify -DskipTests -Dperturbation.deepScale=1e-30

JAVA_TOOL_OPTIONS='-Xmx2g -Dfractal.gpu.enabled=true -Dfractal.benchmark.output=target/optimization-gpu.csv' mvn -Pgpu-render-benchmark javafx:run
JAVA_TOOL_OPTIONS='-Xmx2g -Dfractal.gpu.enabled=true -Dfractal.aa.profile=true -Dfractal.benchmark.scenes=overview-aa,overview-refined -Dfractal.benchmark.output=target/optimization-aa-profile.csv' mvn -Pgpu-render-benchmark javafx:run
JAVA_TOOL_OPTIONS='-Xmx2g -Dfractal.gpu.enabled=true -Dfractal.residentBenchmark.output=target/optimization-resident.csv' mvn -Pgpu-resident-benchmark javafx:run
mvn -Ppalette-benchmark javafx:run
mvn -Pgpu-smoke test
mvn -Pfp32-native -Dfp32.native.full=true test
```

The BLA-disable property affects the benchmark's base backend only; its later
AA sampler still uses the production BLA policy. See
[formula benchmark instructions](FORMULA_BENCHMARK.md),
[8.5 reproduction](GPU_RENDER_BENCHMARK_8_5_RESULTS.md#reproduce) and
[runtime validation](GPU_RUNTIME.md) for native/FX prerequisites and limits.
