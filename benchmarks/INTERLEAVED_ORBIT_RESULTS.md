# Interleaved-orbit scalar ILP spike

Bounded P3 spike for ROADMAP §9.5 and the "Scalar instruction-level
parallelism" row of [the optimization analysis](CPU_GPU_OPTIMIZATION_ANALYSIS.md).

## Hypothesis

The direct-double Mandelbrot and Julia inner loops are latency-bound on one
floating-point dependency chain per pixel. Advancing two or four independent
pixel orbits in the same loop may hide that latency on out-of-order cores
without native code, FMA or SIMD. Each lane must keep the exact scalar
operation order, so escape iterations, escape flags and smooth values stay
bit-identical.

## Predeclared workload and gate

This section was written before any interleaved measurement was taken.

- Kernels: the current scalar path (`FractalCalculator`, the production
  direct-double tile kernel) versus an experimental 2-lane and 4-lane
  interleaved kernel. Each lane is refilled with the next pixel of the tile in
  raster order when it escapes or reaches the iteration cap. Mandelbrot keeps
  the main-cardioid/period-2-bulb rejection when a pixel is loaded. No
  `Math.fma`, no reordering of per-pixel arithmetic.
- Scenes: the six [formula-benchmark](FORMULA_BENCHMARK.md) scenarios
  (Mandelbrot and Julia at 1x, 100x and 10,000x with the same centers and the
  same adaptive iteration policy: 300 base iterations plus 50 per binary zoom
  level), at 1920x1080 and at the 2560x1440 HiDPI equivalent.
- Passes: a full-frame pass (one region, as in the formula benchmark) and a
  32x32-tile pass (the production tile size, tiles calculated sequentially),
  which includes per-tile lane refill tails.
- Execution: single-threaded, in one JVM per resolution. Two warmups per
  variant, then seven paired rounds; each round runs scalar, 2-lane and
  4-lane once in a rotated order so that ordering and thermal drift do not
  favor one variant. The decision uses the median of the per-round paired
  speedups (scalar time / variant time). A second JVM launch at 1920x1080
  checks launch-to-launch stability.
- Correctness gate: before timing, every pixel of every benchmark frame and
  pass must have equal iteration counts, escape flags and smooth values
  (compared by raw IEEE bits) to the scalar kernel. Any mismatch aborts the
  benchmark. The existing test suite must pass unchanged.
- Decision gate for a lane count: median paired speedup of at least 1.15 on
  each iteration-heavy scenario (Mandelbrot 100x and 10,000x, Julia 100x and
  10,000x; mean iteration count above 150) and at least 0.95 (at most a 5%
  regression) on the overview scenarios (Mandelbrot 1x, Julia 1x), at both
  resolutions and in both passes. Otherwise the kernel is rejected and stays
  out of the production path.

## Environment and code state

- Measured on 2026-10-17: Apple M3 Pro (12 cores), 18 GB memory, macOS
  27.0.1, arm64, AC power, Java HotSpot 26.0.1+8-34 (the build targets the Java
  release in `pom.xml`). The benchmark thread was not pinned to a core type.
- Base revision `83204e0` plus the uncommitted spike changes on branch
  `chore/interleaved-orbit-ilp-spike`: the test-only kernel
  `src/test/java/com/shangin/fractal/render/InterleavedOrbitKernel.java`, the
  driver `InterleavedOrbitBenchmark.java` and the
  `interleaved-orbit-benchmark` Maven profile. Production sources are
  unchanged.
- Command (in-process `exec:java`, like the formula benchmark):

  ```shell
  ./mvnw -Pinterleaved-orbit-benchmark verify -DskipTests \
    -DinterleavedOrbit.width=1920 -DinterleavedOrbit.height=1080
  ```

  Other properties: `interleavedOrbit.baseIterations`, `.warmups`, `.rounds`
  and `.passes` (`full,tiled`).

## Candidate development before the gate runs

The first 4-lane version refilled each lane inline in the shared loop. A
480x270 smoke run (three rounds, exploratory only) showed 1.7-2.1x on the
iteration-heavy scenes but 0.68x on the Mandelbrot overview.
`-XX:+PrintInlining` showed that C2 inlined the pixel loader and sample store
(including the smooth-value logarithms) at all eight lane refill sites. With
those methods excluded from inlining by `-XX:CompileCommand=dontinline`, the
overview returned to 1.00x and the heavy scenes improved, which identified code
growth rather than the interleaving itself as the cause. No disassembler
(`hsdis`) was available, so register allocation was not inspected directly.

The measured candidate therefore keeps lane state in locals only inside the
hot loop. When any lane finishes, all lanes are written to small arrays and a
single shared refill routine stores finished samples and loads the next
pixels. Idle lanes iterate the fixed point `z = 0, c = 0` until the remaining
live lanes finish. The gate and the workload above were not changed after
these exploratory runs.

## Correctness

For all three JVM launches, all six scenes and both passes, the 2-lane and
4-lane output matched the scalar output for every pixel: iteration count,
escape flag, smooth value and orbit-trap value compared by raw IEEE bits.
`InterleavedOrbitKernelTest` repeats the comparison at 192x108 for full frames,
32-pixel and 5-pixel tiles, iteration caps 0-3 and 17, a different Julia
constant, the production row-completion order with already-ready pixels, and
cancellation: only fully calculated rows are reported, and each of them
matches the scalar samples.

## Results

Median per-round paired speedup (scalar time / interleaved time), seven
rounds per launch. `A` and `B` are the two 1920x1080 launches, `H` is
2560x1440.

| Pass | Scene | Mean iterations | Scalar p50 A | 2-lane A / B / H | 4-lane A / B / H |
| --- | --- | ---: | ---: | ---: | ---: |
| full | Mandelbrot 1x | 48.3 | 75.46 ms | 0.926 / 0.883 / 0.896 | 0.986 / **0.931** / **0.935** |
| full | Mandelbrot 100x | 284.1 | 368.09 ms | 1.611 / 1.599 / 1.564 | 2.300 / 2.245 / 2.181 |
| full | Mandelbrot 10,000x | 156.2 | 843.07 ms | 1.724 / 1.692 / 1.682 | 2.520 / 2.509 / 2.315 |
| full | Julia 1x | 22.6 | 160.87 ms | 1.241 / 1.236 / 1.192 | 1.491 / 1.445 / 1.399 |
| full | Julia 100x | 272.6 | 1494.36 ms | 1.863 / 1.838 / 1.827 | 2.986 / 2.952 / 2.845 |
| full | Julia 10,000x | 283.1 | 1538.81 ms | 1.858 / 1.849 / 1.855 | 3.024 / 2.991 / 2.887 |
| tiled | Mandelbrot 1x | 48.3 | 83.12 ms | 0.982 / 0.985 / 1.135 | 1.036 / 1.072 / 1.228 |
| tiled | Mandelbrot 100x | 284.1 | 377.43 ms | 1.610 / 1.580 / 1.577 | 2.405 / 2.331 / 2.298 |
| tiled | Mandelbrot 10,000x | 156.2 | 846.94 ms | 1.702 / 1.689 / 1.685 | 2.621 / 2.551 / 2.493 |
| tiled | Julia 1x | 22.6 | 164.73 ms | 1.242 / 1.203 / 1.212 | 1.506 / 1.466 / 1.461 |
| tiled | Julia 100x | 272.6 | 1486.41 ms | 1.826 / 1.814 / 1.822 | 2.952 / 2.806 / 2.834 |
| tiled | Julia 10,000x | 283.1 | 1552.04 ms | 1.836 / 1.826 / 1.795 | 3.012 / 2.891 / 2.842 |

Observations:

- The hypothesis is confirmed on this machine: the scalar loop is
  latency-bound. Four interleaved orbits give 2.2-3.0x on every
  iteration-heavy scene and two give 1.56-1.86x, far above the 1.15 gate,
  with identical output. Gains are stable across launches and resolutions.
- The Mandelbrot overview is the weak case. Most of its exterior pixels escape
  within a few iterations (median 3), so lanes are refilled almost every step
  and the shared refill exchange dominates. In the 32-pixel tile pass, which
  matches the production tile size, 4 lanes are 1.04-1.23x and 2 lanes
  0.98-1.14x. In the full-frame pass, 4 lanes are 0.93-0.99x and 2 lanes
  0.88-0.93x. The scalar kernel is itself 10-25% faster on this scene in the
  full-frame pass than in the tile pass, while the interleaved kernels change
  little; likely causes are the long rows and the constant cancellation
  supplier that the JIT can fold, but this was not isolated.
- The Julia overview gains (1.4-1.5x with 4 lanes) despite even shorter mean
  orbits; its interior is small and its pixels need no cardioid test.

## Decision

**Rejected for production as measured; follow-up recommended.** The
predeclared gate requires at most a 5% overview regression in both passes.
The 4-lane kernel misses it in the full-frame Mandelbrot overview (0.931 and
0.935 in two of three launches) and the 2-lane kernel misses it in all three
launches, so neither lane count passes. The kernel stays in test sources only
and the production direct-double path is unchanged.

The iteration-heavy result is large enough to justify a separate, newly
predeclared follow-up rather than closing the direction. It should
(1) reduce refill cost on short orbits without changing per-pixel arithmetic,
for example by running the first few iterations of a newly loaded pixel in a
scalar pre-loop so that quickly escaping pixels never enter a lane, or by
refilling only finished lanes; (2) measure the production
`ParallelFractalCalculator` path with all workers, time to the first region
and cancellation latency, since this spike measured one thread only; and
(3) define the production scope: Mandelbrot and Julia with `OrbitTrap.NONE`,
excluding distance estimation, orbit traps and other formulas, which keep the
scalar path. Results apply to Apple M3 Pro and HotSpot 26.0.1 only; x86 (16
vector registers without AVX-512) and other JVMs need their own measurements
before any promotion.
