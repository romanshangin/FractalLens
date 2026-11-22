# GPU Julia kernels: certified base pass and approximate preview

Date: 2026-11-19. Goal: shortest possible Julia render time as groundwork for a
live Julia preview driven by a point on the Mandelbrot set.

## Candidates

| Backend | Contract | Output path |
| --- | --- | --- |
| `CPU` | Production direct-double `DirectDoubleRenderBackend` (control) | Samples, CPU `FractalColorizer` → ARGB |
| `GPU_CERTIFIED` | `GpuMandelbrotRenderBackend` with the Julia preset: FP32 interval certificate, exact CPU recovery of rejects; exact iterations/escape, smooth error ≤ 0.01 | 192x192 region batches → sample readback → host certification/recovery → CPU coloring → ARGB |
| `GPU_PREVIEW` | `GpuRuntime.renderJuliaPreview`: uncertified FP32, one dispatch, GPU smooth palette coloring | Axis upload → one dispatch → ARGB readback |

Before implementation, a CPU simulation of the certified FP32 interval
recurrence at 480x320 predicted that dendrite-like sets (c = −0.8+0.156i,
−0.7269+0.1889i, −0.4+0.6i, 0.285+0.01i) reject 13–24% of pixels, but those
pixels carry 69–98% of the CPU iteration work. Only attracting-basin sets
(rabbit, basilica) left 0.2–4% of the work on the CPU. The certified path
therefore cannot be much faster than CPU for most Julia sets even with zero
GPU overhead. The owner chose to implement and measure both paths.

## Predeclared gates

Declared before the measured campaign (a 756x491 smoke run had been observed).
All gates use paired medians of the rotated measured triples.

- **Correctness, certified:** every measured frame matches the CPU frame
  exactly in escape/iterations with smooth error ≤ 0.01. Any mismatch aborts.
- **Correctness, preview (sanity, not exactness):** at most 5% of pixels
  differ from the CPU ARGB by more than one channel level in every frame.
- **Certified retain:** at least 15% faster than CPU on every scene at
  1512x982 and 3024x1964 (the 8.6/9.7 cross-scene rule). Otherwise it stays
  opt-in only and is not an optimization.
- **Preview retain (as a preview capability):** at 300 iterations, median
  ≤ 16.7 ms (one 60 Hz frame) on every scene at 3024x1964, and at least 3x
  faster than CPU on every scene at 1512x982 and 3024x1964.

## Workload and protocol

- Viewport `0, 0, 2.4` (Julia default view), ICE palette, default smooth
  coloring, no AA, no orbit traps, ARGB as the end point; JavaFX publication
  excluded. Sizes 756x491 (logical window), 1512x982, 3024x1964.
- Scenes: `dendrite` −0.8+0.156i, `spiral` −0.7269+0.1889i, `rabbit`
  −0.123+0.745i, `dust` 0.285+0.01i, `sweep` (c moves around |c| = 0.7885 by
  9° per frame, simulating a drag; all three backends use the same c per triple).
- Per scene and size: one cold triple, 3 warmup triples, 30 measured triples.
  Backend order rotates every triple. Two independent JVM starts at 300
  iterations and one at 1000 iterations.
- Run under `caffeinate -di` on AC power; no other timing suite or test run
  overlapped the campaign.

```sh
JAVA_TOOL_OPTIONS='-Xmx2g -Dfractal.gpu.enabled=true -Dfractal.juliaBenchmark.sizes=756x491,1512x982,3024x1964 -Dfractal.juliaBenchmark.warmup=3 -Dfractal.juliaBenchmark.samples=30 -Dfractal.juliaBenchmark.iterations=300 -Dfractal.juliaBenchmark.output=<private csv>' \
  caffeinate -di ./mvnw -Pgpu-julia-benchmark javafx:run
```

## Environment and code state

Apple M3 Pro, 12 logical processors, macOS 27.2.1 (as reported by Java),
ARM64 Java 26.0.1, LWJGL 3.4.2 / FFM, MoltenVK Vulkan 1.1.350, FP32 only (no
`shaderFloat64`), 2 GiB heap, AC power, no thermal or performance warnings
recorded before or after. `caffeinate -di` held system and display assertions
for the whole controller script; none remained afterwards.

Code: branch `feature/gpu-julia-kernel` on base and merge base `a902a07`, with
the uncommitted implementation, which then still contained the certified
path (`julia-precision.comp`, a second pipeline in `VulkanMandelbrotKernel`,
the `fractal.gpu.julia.enabled` opt-in) and a three-backend benchmark driver.
The certified path was removed after the campaign; the delivered driver
measures CPU and preview only. The preview kernel is unchanged since the
campaign. Raw CSVs, logs and power/thermal snapshots are in a
private archive (`benchmarks/gpu-julia-private-20261119/`), not in the public
checkout.

## Results at 300 iterations

Medians in milliseconds of 30 rotated triples per row, shown as start A / start
B. "Certified / CPU" above 1.00 is slower than CPU; "CPU / preview" is the
preview speedup.

| Size | Scene | CPU | Certified | Certified / CPU | Preview | CPU / preview |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 756x491 | dendrite | 6.48 / 6.20 | 18.06 / 17.99 | 2.79x / 2.90x | 1.72 / 1.71 | 3.8x / 3.6x |
| 756x491 | spiral | 8.00 / 7.63 | 20.94 / 19.86 | 2.62x / 2.60x | 1.81 / 1.72 | 4.4x / 4.4x |
| 756x491 | rabbit | 7.67 / 7.54 | 16.14 / 14.45 | 2.10x / 1.92x | 0.47 / 0.41 | 16.3x / 18.5x |
| 756x491 | dust | 4.72 / 4.27 | 12.50 / 12.17 | 2.65x / 2.85x | 0.88 / 0.86 | 5.3x / 5.0x |
| 756x491 | sweep | 3.90 / 3.86 | 10.87 / 10.26 | 2.79x / 2.66x | 0.68 / 0.64 | 5.7x / 6.0x |
| 1512x982 | dendrite | 24.27 / 23.66 | 61.63 / 60.47 | 2.54x / 2.56x | 4.46 / 4.45 | 5.4x / 5.3x |
| 1512x982 | spiral | 29.76 / 29.70 | 71.74 / 71.63 | 2.41x / 2.41x | 5.13 / 5.19 | 5.8x / 5.7x |
| 1512x982 | rabbit | 31.97 / 29.16 | 53.38 / 50.81 | 1.67x / 1.74x | 1.25 / 1.11 | 25.6x / 26.3x |
| 1512x982 | dust | 18.03 / 16.36 | 47.93 / 45.21 | 2.66x / 2.76x | 2.44 / 2.41 | 7.4x / 6.8x |
| 1512x982 | sweep | 16.24 / 14.46 | 42.76 / 39.24 | 2.63x / 2.71x | 1.73 / 1.56 | 9.4x / 9.3x |
| 3024x1964 | dendrite | 96.62 / 91.63 | 235.80 / 225.25 | 2.44x / 2.46x | 11.52 / 9.32 | 8.4x / 9.8x |
| 3024x1964 | spiral | 122.40 / 117.99 | 269.65 / 266.24 | 2.20x / 2.26x | 12.87 / 10.10 | 9.5x / 11.7x |
| 3024x1964 | rabbit | 120.52 / 116.49 | 178.15 / 169.15 | 1.48x / 1.45x | 3.86 / 2.75 | 31.2x / 42.3x |
| 3024x1964 | dust | 68.73 / 62.49 | 180.46 / 169.72 | 2.63x / 2.72x | 7.08 / 5.78 | 9.7x / 10.8x |
| 3024x1964 | sweep | 61.81 / 56.96 | 153.48 / 145.24 | 2.48x / 2.55x | 4.37 / 3.67 | 14.2x / 15.5x |

Certified recovery share is deterministic per scene: dendrite 20.0–20.1%,
spiral 23.0–23.1%, rabbit 2.6%, dust 19.3%, sweep 8.6% (median) of pixels.
Preview pixels more than one channel level from CPU: at most 1.36% per frame
(dendrite), 0.00% for rabbit. Pixels differing from CPU in any channel by any
amount: at most 2.61% (dendrite, 1512x982), 0.28% for rabbit (re-read from the
same private CSVs after the campaign; at 1000 iterations at most 7.21%); outliers sit on the set boundary, where they can
reach the full 255-level range because FP32 and binary64 orbits diverge there.

At 3024x1964 the preview's host-timed dispatch (encoding, submission and fence
wait, kernel included) is 2.1–11.5 ms; ARGB readback (a 24 MB copy from
shared memory) is about 0.5 ms; axis upload and setup are negligible. The
preview is compute-bound. Its first call in a process took about 120 ms
(shader compilation and allocation), so a live preview must warm the kernel
before the first drag. Native memory is 24,038,640 bytes at 3024x1964.

## Results at 1000 iterations (one start, outside the gates)

| Size | Scene | CPU | Certified (x CPU) | Preview (CPU / preview) | Preview outliers |
| --- | --- | ---: | ---: | ---: | ---: |
| 1512x982 | dendrite | 25.93 | 69.30 (2.67x) | 6.36 (4.1x) | 3.15% |
| 1512x982 | spiral | 36.91 | 91.36 (2.48x) | 8.35 (4.4x) | 5.25% |
| 1512x982 | rabbit | 74.00 | 80.55 (1.09x) | 1.04 (71.2x) | 0.00% |
| 1512x982 | dust | 16.05 | 45.14 (2.81x) | 2.36 (6.8x) | 0.25% |
| 1512x982 | sweep | 14.63 | 38.29 (2.62x) | 1.53 (9.6x) | 2.03% |
| 3024x1964 | dendrite | 100.80 | 254.66 (2.53x) | 14.21 (7.1x) | 3.14% |
| 3024x1964 | spiral | 146.64 | 342.82 (2.34x) | 19.23 (7.6x) | 5.25% |
| 3024x1964 | rabbit | 299.36 | 277.51 (0.93x) | 2.31 (129.3x) | 0.00% |
| 3024x1964 | dust | 64.06 | 170.00 (2.65x) | 6.19 (10.4x) | 0.24% |
| 3024x1964 | sweep | 58.82 | 148.24 (2.52x) | 3.59 (16.4x) | 2.03% |

Rabbit is the only case where certified GPU beats CPU (7%, below the 15%
threshold): its interior is certified by the bounded enclosure, while CPU must
iterate every interior pixel to the limit. At 1000 iterations the spiral
preview exceeds both the 5% outlier sanity bound (5.25%) and the 16.7 ms
budget at 3024x1964 (19.23 ms).

## Correctness

All 1,530 certified frames (cold, warmup and measured) matched the CPU frame
exactly in escape and iterations; maximum smooth error 0.000298. Before
removal, a native gate also checked five constants at 64/300/1000 iterations,
a zoomed view and pan reuse against direct CPU samples; it passed.

## Decisions

- **Certified Julia base pass: reject.** Correct in every frame but
  1.45–2.90x slower than CPU at 300 iterations in all 15 cases, and still
  slower in 9 of 10 cases at 1000 iterations. After this report the owner
  chose to remove it: its shader, kernel/backend wiring, opt-in property and
  tests are not in the delivered code. CPU stays the Julia default.
- **Julia preview kernel: retain as a preview capability at 300 iterations.**
  All predeclared preview gates pass in both starts: 3024x1964 medians
  2.75–12.87 ms, 5.3–42x faster than CPU at 1512x982 and 3024x1964, at most
  1.36% boundary outliers. At the time of this report it was an API only; it
  later became the opt-in Render > Julia GPU mode. At 1000
  iterations it is **inconclusive**: the spiral scene fails the outlier bound
  and the 60 Hz budget at 3024x1964.

## Why the certified path cannot win, and what this implies

1. **Recovery bound.** The FP32 certificate rejects boundary pixels, which
   carry most Julia work. Even with free GPU work, the CPU recovery of 20% of
   pixels costs most of a CPU frame for dendrite-like sets.
2. **Pipeline overhead.** Even rabbit (2.6% recovery) is 1.45–1.74x slower at
   300 iterations: synchronous 192x192 region round trips, the doubled
   (raw + interval) recurrence, host certification of every sample and CPU
   coloring together cost more than the CPU calculation itself. Only a
   resident whole-frame design could remove this, and it still could not lift
   the recovery bound.
3. **Live preview architecture.** The measured fast path is the opposite
   design: one dispatch, palette coloring on GPU, ARGB only, explicitly
   approximate. A live Julia preview should use it during the gesture and then
   replace it with the exact CPU frame (about 60–120 ms at 3024x1964 and 300
   iterations) once the pointer stops. Remaining costs for that feature: the
   one-time warm-up, the int[] copy into JavaFX, JavaFX publication itself,
   and adaptive resolution or iteration limits for 1000-iteration scenes.
   None of these were measured here.

## Not covered

JavaFX publication and scanout, gesture-to-visible latency, Windows and
Intel/AMD Mac hardware, sustained thermal behavior beyond this campaign, memory
growth over long sessions, AA, histogram coloring and orbit traps.
