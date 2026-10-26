# Interleaved-orbit follow-up: cheaper refill and the parallel path

Follow-up to the [first interleaved-orbit spike](INTERLEAVED_ORBIT_RESULTS.md)
for ROADMAP §9.5 and the "Scalar instruction-level parallelism" row of
[the optimization analysis](CPU_GPU_OPTIMIZATION_ANALYSIS.md). The first
spike's gate, results and decision are unchanged. This file has its own
protocol and gate.

## Hypothesis

The first spike's interleaved kernel lost on the Mandelbrot overview because
most exterior pixels there escape within a few iterations, so lanes are
refilled almost every step and the shared refill exchange dominates. If every
newly loaded pixel first runs up to `K` iterations in a scalar pre-loop, then
pixels that escape quickly never enter a lane. Lanes then hold only longer
orbits, and they are refilled far less often. Iteration-heavy scenes keep most
of their gain, because a long orbit spends only its first `K` steps in the
scalar pre-loop.

The pre-loop runs the same loop condition and the same operation order as
`MandelbrotFormula` and `JuliaFormula`, without FMA. It hands a surviving
orbit to a lane with its current `z` and iteration count. Escape iterations,
escape flags and smooth values therefore stay bit-identical to the scalar
kernel. The hot loop already reloads only lanes that finished; that part is
unchanged.

## Predeclared protocol and gate

This section was written before any measurement of the follow-up kernel.

### Stage A: single-threaded kernel gate

- Kernels: the scalar production direct-double tile kernel
  (`FractalCalculator`), the first spike's 4-lane kernel without a pre-loop
  (reference only, not gated), and 2-lane and 4-lane kernels with a scalar
  pre-loop of `K` iterations. Mandelbrot keeps the main-cardioid and
  period-2-bulb rejection before the pre-loop. No `Math.fma`, no reordering of
  per-pixel arithmetic.
- Choice of `K`: one value from {4, 8, 16, 32}, chosen only from exploratory
  480x270 smoke runs (three rounds each) before any gate run. The rule is
  fixed now: pick the `K` that maximizes the minimum 4-lane median speedup
  across all six scenes and both passes; the same `K` is used for 2 lanes.
  The smoke numbers and the chosen `K` are recorded below before the gate runs.
  The gate measurements are never used to change `K`.
- Scenes, iteration policy, resolutions and passes: as in the first spike.
  These are the six formula-benchmark scenes (Mandelbrot and Julia at 1x, 100x
  and 10,000x; 300 base iterations plus 50 per binary zoom level). Each runs
  at 1920x1080 and 2560x1440, in a full-frame pass and a 32x32-tile pass.
- Execution: single-threaded, one JVM per launch, two warmups per variant and
  seven paired rounds. Each round runs every variant once in a rotated order.
  The metric is the median of the per-round paired speedups (scalar time /
  variant time). There are three launches: `A` and `B` at 1920x1080 and `H` at
  2560x1440.
- Correctness: before timing, every pixel of every frame and pass must match
  the scalar kernel in iteration count, escape flag, smooth value and
  orbit-trap value, compared by raw IEEE bits. Any mismatch aborts the run. The
  existing test suite must pass unchanged, and the kernel test must cover the
  pre-loop boundaries: caps below, at and just above `K`, and orbits escaping
  exactly at step `K`.
- Gate for a lane count, with the same thresholds as the first spike: in all
  three launches and in both passes, the median paired speedup must be at
  least 1.15 on each iteration-heavy scene (Mandelbrot and Julia at 100x and
  10,000x). It must be at least 0.95 on each overview scene (Mandelbrot 1x,
  Julia 1x). If both lane counts pass, 4 lanes go to stage B; otherwise the
  passing lane count goes. If neither passes, stage B does not run and the
  follow-up is rejected.

### Stage B: multi-threaded production path

Stage B runs only if stage A passes.

- Prototype: the stage-A kernel moves to `src/main` behind a non-default
  switch. It is used by `ParallelFractalCalculator` only for the Mandelbrot
  and Julia presets with `OrbitTrap.NONE`. Every other formula, orbit traps,
  distance estimation, antialiasing and export keep the scalar path. With the
  switch off, production behavior is unchanged.
- Workload: the six stage-A scenes at 1920x1080 and 2560x1440. Each frame is a
  fresh `RenderFrame` with no reusable pixels and the default center priority.
  It is calculated through `ParallelFractalCalculator` with the production
  tile size (32) and the default worker count (`availableProcessors - 1`).
  Conjugate symmetry applies as in production.
- Execution: one JVM per resolution, two warmups per variant, then fifteen
  paired rounds alternating which variant runs first. Separate calculator
  instances are used for scalar and interleaved.
- Metrics per scene:
  - total frame time: wall time of `calculate`;
  - time to first region: from the start of `calculate` to the first
    `regionCompleted` callback;
  - cancellation latency: in fifteen separate paired trials, the frame starts
    on another thread. The cancellation flag is set as soon as the first
    region is reported. The metric is the time from setting the flag until
    `calculate` returns.
- Correctness: every completed stage-B frame must match the scalar frame
  pixel for pixel, as in stage A. Cancelled frames must not mark any pixel
  ready whose samples differ from the scalar result.
- Gate, at both resolutions:
  - median paired total-frame speedup of at least 1.15 on each
    iteration-heavy scene and at least 0.95 on each overview scene;
  - median paired time-to-first-region ratio (scalar / interleaved) of at
    least 0.90 on every scene;
  - interleaved cancellation latency with a median at most the scalar median
    plus 2 ms, and a maximum at most the scalar maximum plus 5 ms, on every
    scene.
- Decision: if stage B passes, the switch stays in production as a
  non-default prototype. Promotion to default needs its own gate on other
  hardware and JVMs. If stage B fails, the production prototype is removed and
  only the test-only kernel and this evidence remain.

## Environment and code state

- Apple M3 Pro (6 performance and 6 efficiency cores), 18 GB memory, macOS
  27.0.1, arm64, AC power, Java HotSpot 26.0.1+8-34. Threads are not pinned to
  a core type.
- Base revision `83204e0`, the first spike's uncommitted changes, and this
  follow-up on branch `chore/interleaved-orbit-refill-spike`.

## Choice of K (exploratory smoke, before the gate)

Recorded before any stage-A gate run. 480x270, two warmups, three paired
rounds per `K`, one launch per `K`, same machine and code state. Values are
the median paired speedups over scalar for the 4-lane pre-loop kernel; the
column "min" is the selection criterion (minimum over all six scenes and both
passes).

| K | full M 1x | tiled M 1x | full J 1x | tiled J 1x | heavy scenes (range) | min |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 4 | 1.129 | 1.214 | 1.735 | 1.597 | 2.139-2.957 | 1.129 |
| 8 | 1.170 | 1.232 | 1.739 | 1.669 | 2.283-2.962 | 1.170 |
| 16 | 1.139 | 1.315 | 1.685 | 1.647 | 2.250-2.898 | 1.139 |
| 32 | 1.178 | 1.265 | 1.564 | 1.516 | 2.059-2.650 | **1.178** |

The rule selects **K = 32** for both lane counts. The margin over `K = 8` is
small (1.178 versus 1.170, three rounds each) and within smoke noise, and
`K = 32` gives up some of the iteration-heavy gain (2.06-2.65x versus
2.28-2.96x). The rule is applied as written anyway; `K` is not revisited from
the gate data. In the same smoke runs, the first spike's 4-lane kernel without
a pre-loop measured 0.905-0.930 on the full-frame Mandelbrot overview, so the
reference regression reproduced.

## Stage A results

Command, run once per launch (`A` and `B` at 1920x1080, `H` at
2560x1440):

```shell
./mvnw -Pinterleaved-orbit-benchmark verify -DskipTests \
  -DinterleavedOrbit.width=1920 -DinterleavedOrbit.height=1080 \
  -DinterleavedOrbit.prefix=32
```

Correctness: in all three launches, every pixel of every frame and pass of the
2-lane and 4-lane pre-loop kernels and the reference 4-lane kernel matched the
scalar kernel (iteration count, escape flag, smooth value and orbit-trap value
compared by raw IEEE bits). `InterleavedOrbitKernelTest` covers lanes 2 and 4
with prefixes 0, 8 and 32 on the benchmark scenes and in tiles, and prefixes
1-32 with caps `K - 1`, `K`, `K + 1` and 300. Its fixtures are asserted to
contain orbits that escape exactly at step `K`.

Median per-round paired speedup over scalar, seven rounds per launch. "Ref" is
the first spike's 4-lane kernel without a pre-loop (not gated).

| Pass | Scene | Scalar p50 A | Ref 4-lane A / B / H | 2-lane K=32 A / B / H | 4-lane K=32 A / B / H |
| --- | --- | ---: | ---: | ---: | ---: |
| full | Mandelbrot 1x | 73.93 ms | 0.930 / 0.923 / 0.932 | 1.097 / 1.082 / 1.086 | 1.189 / 1.144 / 1.155 |
| full | Mandelbrot 100x | 366.70 ms | 2.275 / 2.198 / 2.089 | 1.538 / 1.532 / 1.489 | 2.044 / 2.020 / 1.887 |
| full | Mandelbrot 10,000x | 841.23 ms | 2.474 / 2.468 / 2.311 | 1.652 / 1.622 / 1.606 | 2.153 / 2.162 / 1.931 |
| full | Julia 1x | 161.98 ms | 1.440 / 1.433 / 1.403 | 1.336 / 1.317 / 1.329 | 1.544 / 1.509 / 1.413 |
| full | Julia 100x | 1482.41 ms | 2.871 / 2.850 / 2.755 | 1.769 / 1.760 / 1.767 | 2.591 / 2.564 / 2.408 |
| full | Julia 10,000x | 1543.70 ms | 2.957 / 2.842 / 2.830 | 1.805 / 1.755 / 1.789 | 2.659 / 2.613 / 2.554 |
| tiled | Mandelbrot 1x | 85.62 ms | 1.057 / 1.092 / 1.239 | 1.156 / 1.182 / 1.344 | 1.251 / 1.261 / 1.450 |
| tiled | Mandelbrot 100x | 379.32 ms | 2.323 / 2.305 / 2.294 | 1.536 / 1.555 / 1.574 | 2.052 / 2.057 / 2.026 |
| tiled | Mandelbrot 10,000x | 849.46 ms | 2.523 / 2.504 / 2.410 | 1.657 / 1.644 / 1.646 | 2.193 / 2.202 / 2.062 |
| tiled | Julia 1x | 165.70 ms | 1.460 / 1.465 / 1.442 | 1.321 / 1.320 / 1.308 | 1.527 / 1.510 / 1.457 |
| tiled | Julia 100x | 1482.54 ms | 2.880 / 2.834 / 2.757 | 1.772 / 1.764 / 1.748 | 2.594 / 2.578 / 2.430 |
| tiled | Julia 10,000x | 1518.62 ms | 2.911 / 2.877 / 2.668 | 1.758 / 1.760 / 1.738 | 2.668 / 2.653 / 2.557 |

**Stage A passes for both lane counts.** The minimum over all launches and
passes is 1.144 on the overview scenes and 1.887 on the iteration-heavy scenes
with 4 lanes. With 2 lanes it is 1.082 and 1.489. Per the protocol, 4 lanes
with `K = 32` go to stage B.

Observations:

- The pre-loop removes the full-frame Mandelbrot overview regression: 0.92-0.93x
  without it and 1.14-1.19x with it, measured in the same rounds.
- The cost is visible on the heavy scenes. The pre-loop 4-lane kernel
  is 10-17% slower than the reference 4-lane kernel there (for example 2.04x
  against 2.28x on full-frame Mandelbrot 100x). Each long orbit spends its
  first 32 steps in the scalar loop, and lanes are fed less regularly. A
  smaller `K` might keep more of that gain, but `K` stays fixed at 32 by the
  protocol.

## Stage B prototype

- `InterleavedOrbitKernel` moved from test sources to
  `src/main/java/com/shangin/fractal/render/` (package-private). It is
  unchanged apart from `forDefinition`. That method returns a 4-lane kernel
  with `K = 32` for the `MANDELBROT` and `JULIA` presets with
  `OrbitTrap.NONE`, and `null` for everything else, including custom formula
  definitions.
- `ParallelFractalCalculator` uses it for the base tile pass only when the
  `fractal.cpu.interleavedOrbits` system property is `true`, for example
  `JAVA_TOOL_OPTIONS=-Dfractal.cpu.interleavedOrbits=true ./mvnw javafx:run`.
  The property is off by default. Single-sample paths
  (`FractalCalculator.calculateSample`, antialiasing, export, GPU fallbacks)
  and deep-zoom backends always use the scalar kernel.
- `InterleavedOrbitParallelTest` checks the scope for every preset and orbit
  trap and that the switch is off by default. With the switch on, it checks
  that frames for every preset are identical to scalar frames, that partially
  ready frames fill only their missing spans, and that cancellation marks only
  exact pixels ready.

## Stage B results

Command, one JVM per resolution:

```shell
./mvnw -Pinterleaved-orbit-benchmark verify -DskipTests \
  -DinterleavedOrbit.mode=parallel \
  -DinterleavedOrbit.width=1920 -DinterleavedOrbit.height=1080
```

Eleven workers, 32-pixel tiles, two warmups and fifteen paired rounds per
scene. Correctness: every completed interleaved frame matched the scalar frame
pixel for pixel. In every cancelled trial, all ready pixels matched the scalar
frame.

Total frame time and time to first region:

| Resolution | Scene | Scalar total p50 | Interleaved total p50 | Total speedup med (min-max) | First-region ratio med |
| --- | --- | ---: | ---: | ---: | ---: |
| 1920x1080 | Mandelbrot 1x | 11.86 ms | 11.38 ms | 1.030 (0.917-1.228) | 1.002 |
| 1920x1080 | Mandelbrot 100x | 50.32 ms | 28.88 ms | 1.730 (1.677-2.959) | 1.093 |
| 1920x1080 | Mandelbrot 10,000x | 105.91 ms | 53.89 ms | 1.963 (1.945-2.082) | 1.564 |
| 1920x1080 | Julia 1x | 25.62 ms | 20.69 ms | 1.252 (1.191-1.586) | 1.281 |
| 1920x1080 | Julia 100x | 181.42 ms | 79.71 ms | 2.281 (2.198-2.308) | 1.825 |
| 1920x1080 | Julia 10,000x | 184.66 ms | 79.32 ms | 2.331 (2.280-2.347) | 1.865 |
| 2560x1440 | Mandelbrot 1x | 20.95 ms | 19.27 ms | 1.073 (0.950-1.203) | 0.997 |
| 2560x1440 | Mandelbrot 100x | 88.63 ms | 50.09 ms | 1.768 (1.695-1.802) | 0.982 |
| 2560x1440 | Mandelbrot 10,000x | 186.18 ms | 94.25 ms | 1.973 (1.888-2.094) | 1.605 |
| 2560x1440 | Julia 1x | 46.06 ms | 34.65 ms | 1.304 (1.243-1.537) | 1.392 |
| 2560x1440 | Julia 100x | 319.84 ms | 139.15 ms | 2.295 (2.050-2.397) | 1.779 |
| 2560x1440 | Julia 10,000x | 327.45 ms | 139.19 ms | 2.354 (2.312-2.364) | 1.764 |

Cancellation latency, from setting the flag on the first reported region
until `calculate` returns (fifteen trials each, median / maximum):

| Resolution | Scene | Scalar | Interleaved |
| --- | --- | ---: | ---: |
| 1920x1080 | Mandelbrot 1x | 0.21 / 0.77 ms | 0.22 / 0.32 ms |
| 1920x1080 | Mandelbrot 100x | 0.17 / 0.23 ms | 0.17 / 0.21 ms |
| 1920x1080 | Mandelbrot 10,000x | 0.12 / 0.14 ms | 0.12 / 0.17 ms |
| 1920x1080 | Julia 1x | 0.10 / 0.12 ms | 0.11 / 0.16 ms |
| 1920x1080 | Julia 100x | 0.08 / 0.13 ms | 0.10 / 0.12 ms |
| 1920x1080 | Julia 10,000x | 0.08 / 0.12 ms | 0.10 / 0.13 ms |
| 2560x1440 | Mandelbrot 1x | 0.22 / 0.43 ms | 0.21 / 0.35 ms |
| 2560x1440 | Mandelbrot 100x | 0.18 / 0.22 ms | 0.17 / 0.20 ms |
| 2560x1440 | Mandelbrot 10,000x | 0.14 / 0.17 ms | 0.17 / 0.37 ms |
| 2560x1440 | Julia 1x | 0.13 / 0.17 ms | 0.15 / 0.19 ms |
| 2560x1440 | Julia 100x | 0.14 / 0.18 ms | 0.15 / 0.18 ms |
| 2560x1440 | Julia 10,000x | 0.16 / 2.94 ms | 0.15 / 0.18 ms |

**Stage B passes at both resolutions.** Total-frame speedup on the
iteration-heavy scenes is 1.73-2.35, above 1.15. On the overview scenes it is
1.03-1.30, above 0.95. The time-to-first-region ratio is at least 0.982 on
every scene. Interleaved cancellation medians are within 0.03 ms of scalar,
and maxima within 0.2 ms of scalar (gate limits: 2 ms and 5 ms).

Observations:

- With all workers busy, the gain is smaller than single-threaded
  (for example Julia 10,000x 2.33x versus 2.55-2.67x, and Mandelbrot 1x
  1.03-1.07x versus 1.14-1.45x). Likely causes are the six efficiency cores,
  the shared tail of the last tiles, and the conjugate-symmetry half frame on
  the Mandelbrot overview. They were not isolated.
- The Mandelbrot overview is still the weakest scene. Its per-round minimum
  falls to 0.917 at 1920x1080 and 0.950 at 2560x1440, although the medians pass.
  A frame of 11-21 ms is sensitive to scheduling noise.
- Time to first region improves 1.56-1.87x on Mandelbrot 10,000x and Julia
  100x and 10,000x, and 1.28-1.39x on Julia 1x. It stays equal (0.98-1.09) on
  Mandelbrot 1x and 100x, whose first center tiles are dominated by the
  analytic interior test or by interior pixels at the iteration cap.
- The scalar kernel checks cancellation every eighth pixel, and the
  interleaved kernel every eighth loaded pixel. The one 2.94 ms scalar maximum
  is a single outlier trial.
  These trials cancel on the first reported region only. Cancellation in the
  middle of long interior lane orbits was not swept separately.

## Decision

**Retained as a non-default prototype.** Stage A and stage B pass their
predeclared gates on this machine. The output is bit-identical to the scalar
kernel in every measured frame and in the tests. The switch
`fractal.cpu.interleavedOrbits` stays off by default, and production behavior
is unchanged without it.

Not covered, and required before any promotion to default:

- other hardware and JVMs: x86 (Windows, Intel Mac), other Apple chips, and
  other HotSpot releases. Results apply to Apple M3 Pro with HotSpot 26.0.1
  only;
- end-to-end interaction latency through `FractalRenderService` and the UI
  (gesture to visible update), frame reuse with partially ready frames under
  load, and cancellation in the middle of long interior orbits;
- a smaller `K`: the smoke data suggests `K = 8` may keep more of the
  iteration-heavy gain, and it needs its own predeclared comparison;
- the first spike's `INTERLEAVED_ORBIT_RESULTS.md` still describes the kernel
  as test-only, which was true for that spike. The kernel now lives in
  `src/main` for this prototype.
