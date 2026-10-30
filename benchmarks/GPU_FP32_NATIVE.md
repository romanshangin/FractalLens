# Native Mandelbrot FP32 acceptance contract

> Historical evidence: results and “current source” describe the dated experiment, not validation of this checkout. Only [selected evidence](README.md) is included; “private archive” entries are unavailable here. Original SHAs/commands may require private revisions or inputs. See [provenance and replay limits](../docs/development/PUBLICATION.md).

## Decision

Experimental hybrid uses per-pixel intervals/CPU recovery, never whole-frame FP32
from zoom/spacing alone. Native gate: 89.65% Retina overview accepted, zero false
accepts; seahorse 100×/300 iterations rejects all.

Integrated optional Mandelbrot/recovery passes [8.4 conformance](GPU_RENDER_BENCHMARK_RESULTS.md)
but loses every measured workload; CPU default. Enable/selection/ownership/reuse:
`../docs/platform/GPU_RUNTIME.md`. Initial [CPU screen](GPU_FP32_PRECISION.md).

## Allowed inputs and output tolerance

The probe supports Mandelbrot's direct recurrence with original CPU-double pixel
coordinates, each component in [-4, 4], and iteration budgets 1–10,000. The
recorded scene matrix tests budgets 32, 64, 300 and 1000; budgets 2 and 3 check
escape-on-final-iteration semantics. The shader omits interior shortcuts.

Before accepting any grid output, every axis coordinate must round to FP32 with
error at most 1/16 pixel, and adjacent coordinates must remain distinct. Check
the actual render grid, including pan offsets, dimensions and conjugate rows.

Each accepted pixel must additionally have:

- An unambiguous interval certificate for the original-coordinate recurrence.
- The exact CPU escaped/unescaped classification and iteration count. The
  certificate is stricter than the earlier screen's one-iteration tolerance.
- For escaped samples, absolute smooth-iteration error at most 0.01. The host
  acceptance check reserves a 1e-9 margin when evaluating logarithmic endpoints.
- Finite raw orbit values and valid finite escaped-radius bounds.

The tolerances are engineering choices for palette-independent base samples.
They do not promise one RGB level for arbitrary palette stops or scales. Orbit
traps, histogram bins, distance estimates, AA, other formulas and GPU deep zoom
require separate contracts. Return rejected/unsupported work to the existing
precision-selecting CPU backend using the original job, not rounded coordinates.

## Certificate construction

The shader calculates a raw FP32 result and a separate interval recurrence.
Input intervals enclose the original CPU-double coordinates. The interval
recurrence follows the CPU operation order and expands every add, subtract and
multiply outward by four representable FP32 values. Near-zero results expand
beyond the smallest normal value so bounds do not rely on denormal preservation.
Starting at zero, interval arithmetic includes both input rounding and accumulated
orbit error. Components cannot overflow: while advancing a certificate the upper
squared-radius bound is at most 4 and input components are bounded by 4.

Every elementary float add/subtract/multiply is `precise`. The probe inspects
SPIR-V before creating the pipeline and rejects any `RelaxedPrecision` decoration
or missing `NoContraction` decoration on those arithmetic results. Vulkan requires
these single-precision operations to be correctly rounded; the outward expansion
also covers the smaller CPU-double rounding at each corresponding operation.
See the [Vulkan precision rules](https://docs.vulkan.org/spec/latest/appendices/spirvenv.html)
and [GLSL precise qualifier](https://docs.vulkan.org/glsl/latest/chapters/variables.html).

At each iteration the interval proves that the squared radius is wholly at most
4 or wholly above 4. An interval straddling 4 rejects the pixel. Reaching the
iteration budget preserves the existing CPU convention: that result is unescaped,
even if the last permitted step crosses the radius. For an escaped certificate,
the raw orbit must escape on the certified iteration and its smooth value must
be within 0.01 of both ends of the certified smooth interval. Shader logarithms,
square roots and division are not used: the host derives smooth endpoints from
the squared-radius bounds using the existing double expression.

Independent CPU oracle checks every accepted sample, fails on any violation;
certificate evaluation itself needs no oracle. Probe counts rejects as CPU recovery;
integrated backend recovers/publishes only certified/recovered samples.

## Native results

Recorded 2026-09-03 on Apple M3 Pro, macOS 26.6.2, ARM64 Java 26.0.2,
LWJGL 3.4.2 / FFM, MoltenVK Vulkan 1.1.350. The test JVM denies Unsafe access.
Raw per-case counts and shader SHA-256 are saved in
[GPU_FP32_NATIVE_RESULTS.csv](GPU_FP32_NATIVE_RESULTS.csv).

| Workload | Iteration limit | Pixels | Raw escaped errors | Raw smooth errors | Accepted | False accepts |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Overview 384×256 | 300 | 98,304 | 28 | 422 | 89.42% | 0 |
| Overview 3024×1964 | 300 | 5,939,136 | 1,352 | 23,820 | 89.65% | 0 |
| Panned overview 385×257 (17, -13) | 300 | 98,945 | 30 | 390 | 89.36% | 0 |
| Panned overview 385×257 (-39, 28) | 300 | 98,945 | 30 | 388 | 89.42% | 0 |
| Cardioid cusp 384×256 | 300 | 98,304 | 22 | 266 | 60.81% | 0 |
| Seahorse 100× 384×256 | 300 | 98,304 | 535 | 4,153 | 0% | 0 |
| Seahorse 100× 3024×1964 | 300 | 5,939,136 | 30,305 | 252,718 | 0% | 0 |
| Cardioid/bulb boundary sweep | 1000 | 32,768 | 0 | 0 | 0.17% | 0 |
| Seeded random plane points | 1000 | 65,539 | 9 | 268 | 91.23% | 0 |

Retina overview: 5,324,206 accepted/614,930 rejected; max accepted smooth error
0.000176775 <0.01. Exterior/interior controls pass all four budgets. 34 cases,
14,926,981 comparisons including repeated coordinates/budgets; all 7,180,271 accepts
pass oracle. Seven small scenes, bidirectional pan, boundary/random sweeps/two
exhaustive Retina frames; unlike CPU screen, Retina covers every pixel.

Boundary: 4096 parametric points each cardioid/period-2 bulb, adjacent doubles/
±1e-12 real offsets. Observing no raw errors does not prove that the interval is stable. Rounding collapse/
coarse inputs reject grids before acceptance, even harmless-looking short orbits.

Zero observed false accepts is evidence for the implementation on this device,
not cross-platform certification. Shader/compiler/driver changes require rerunning
the gate. Windows and Intel/AMD Mac validation remain separate roadmap work.

## Reproduce and ownership

```sh
mvn -Pfp32-native -Dfp32.native.full=true test
mvn test
```

The first command requires access to real MoltenVK hardware and fails if it is
unavailable. It never replaces shader execution with CPU calculation. Output is
`target/mandelbrot-fp32-native.csv`; omit `fp32.native.full` to skip the two full
Retina frames. The portable suite skips the native gate.

Shared production/diagnostic GLSL/kernel/gate: `src/main`. Standalone owns Vulkan
instance/device, shares refcounted FFM loader. 16,384-sample batches, two mapped
buffers (1.25 MiB logical, 4 MiB ceiling), noncoherent flush/invalidate. Check
buffer/workgroup/push limits, fence each dispatch, serialize destruction. Post-submit
cancel drains before return/no publish. Tests cover pre/post cancel, changed-cap
buffer reuse, teardown/reopened dispatch.

CSV `wall_ms` includes uploads, raw and interval shader work, readback, CPU oracle
and checking. It is not kernel timing or evidence of end-to-end acceleration.
The integrated backend is now ready for separate end-to-end measurements.

The probe uploads per-pixel coordinates and their bounds from the CPU. A future
shader that reconstructs coordinates from origin/step must also enclose that
additional rounding; the current result does not validate such reconstruction.

## Integration and next decision gate

Optional `GpuMandelbrotRenderBackend` uses existing owner, original-coordinate
recovery/pan overlap, complete 192x192 publication. Bounded GPU/readback overlaps
bounded host certify/convert/recover with reusable primitives; generation cancel
blocks stale output. Accuracy tags isolate CPU-reference/certified reuse. Hardware
gate covers output/recovery/shared palette/loss/reopen/opt-in service selection;
full precision still passes same production shader.

8.5 adds outward analytic cardioid/period-2 certificate, removes duplicate smooth
work/tunes bounded batches and repeats gate: numeric pass, all end-to-end cases
slower, GPU opt-in. `GPU_RENDER_BENCHMARK_8_5_RESULTS.md`.
