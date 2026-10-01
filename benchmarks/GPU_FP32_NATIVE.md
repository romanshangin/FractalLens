# Native Mandelbrot FP32 acceptance contract

> Historical evidence: results and “current source” below refer to the dated
> experiment, not a new validation of this checkout. Only the
> [selected evidence](README.md) is included; entries marked “private archive”
> are unavailable in this repository. Original SHA values and recorded commands
> describe the original run and may require private revisions or inputs. See
> [provenance and replay limits](../PUBLICATION.md).

## Decision

Use a per-pixel interval certificate with CPU recovery for an experimental hybrid
backend. Do not enable plain FP32 for an entire frame based only on zoom or
coordinate spacing. The native probe accepts 89.65% of a complete Retina overview
with zero false acceptances in the recorded suite. The same certificate rejects
all pixels at the existing seahorse center at 100× zoom and 300 iterations.

This contract is now integrated with the optional production Mandelbrot backend
and CPU recovery. The subsequent [8.4 decision gate](GPU_RENDER_BENCHMARK_RESULTS.md)
passes numeric conformance but finds the hybrid backend slower in all measured
workloads. CPU rendering remains the default; see `../GPU_RUNTIME.md` for
the enable command, selection, ownership and reuse rules. The initial CPU experiment is in [GPU_FP32_PRECISION.md](GPU_FP32_PRECISION.md).

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

The CPU oracle is independent of the acceptance test. It checks every accepted
sample and fails the run if even one violates the output contract. It is not
needed to evaluate the interval certificate itself. Rejected pixels are counted
as required CPU recovery in this probe. The integrated backend now implements
that recovery and publishes only certified or recovered samples.

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

The Retina overview accepts 5,324,206 pixels and rejects 614,930. Its maximum
observed accepted smooth error is 0.000176775, below the 0.01 limit. Exterior and
interior control frames pass completely at all four recorded iteration budgets.
The 34-case suite covers 14,926,981 pixel comparisons, including repeated scene
coordinates at different budgets. All 7,180,271 accepted samples passed the CPU
oracle. It includes seven small scenes, both pan directions, boundary
sweeps, fixed-seed random points and two exhaustive Retina frames. Unlike the
earlier CPU screening, the Retina results cover every pixel.

The boundary sweep perturbs 4096 parametric points on each of the cardioid and
period-2 bulb using adjacent doubles and ±1e-12 real-coordinate offsets. It
illustrates conservative rejection: zero observed raw errors does not prove a
stable interval. Input-rounding collapse and excessively coarse coordinates
reject whole grids before acceptance, even when a short raw orbit looks harmless.

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

The GLSL, kernel and acceptance gate now live under `src/main`, shared by
production and the diagnostic. The standalone test adapter owns its
Vulkan instance/device and shares the existing reference-counted FFM loader. Its
fixed 16,384-sample batches use two mapped storage buffers (1.25 MiB logical
storage, a 4 MiB allocation ceiling), with explicit flush/invalidate on non-coherent
memory. It checks device buffer/workgroup/push-constant limits, waits for each
fence, and serializes dispatch and destruction. Cancellation after submission
drains the fence before returning without publishing results. The gate exercises
pre-submit and post-submit cancellation, buffer reuse with changed iteration
limits, complete teardown and a new dispatch after reopening.

CSV `wall_ms` includes uploads, raw and interval shader work, readback, CPU oracle
and checking. It is not kernel timing or evidence of end-to-end acceleration.
The integrated backend is now ready for separate end-to-end measurements.

The probe uploads per-pixel coordinates and their bounds from the CPU. A future
shader that reconstructs coordinates from origin/step must also enclose that
additional rounding; the current result does not validate such reconstruction.

## Integration and next decision gate

The optional `GpuMandelbrotRenderBackend` uses the existing runtime owner,
recovers rejected samples at original coordinates, preserves compatible pan
overlap, and publishes completed 192x192 regions. A bounded GPU worker overlaps
readback with a bounded host pool that certifies, converts and recovers into
reusable primitive staging; generation cancellation suppresses stale output.
Sample-accuracy tags separate certified and CPU-reference cache/reuse requests.
The hardware integration gate checks output, recovery, shared palette dispatch,
loss fallback, reopening and opt-in default-service selection. The standalone
full precision gate still passes against the same production shader.

Roadmap 8.5 adds an outward-rounded analytic cardioid/period-2-bulb certificate,
removes duplicate host smooth calculations, tunes bounded batches, and repeats
the full gate. Conformance still passes, but every end-to-end case remains slower
than CPU; GPU calculation therefore stays opt-in. See
`GPU_RENDER_BENCHMARK_8_5_RESULTS.md`.
