# Mandelbrot FP32 precision screening (roadmap 8.3)

> Historical evidence: results and “current source” below refer to the dated
> experiment, not a new validation of this checkout. Only the
> [selected evidence](README.md) is included; entries marked “private archive”
> are unavailable in this repository. Original SHA values and recorded commands
> describe the original run and may require private revisions or inputs. See
> [provenance and replay limits](../docs/development/PUBLICATION.md).

This document records the initial CPU screening. The subsequent native shader
experiment and per-pixel acceptance contract are in
[GPU_FP32_NATIVE.md](GPU_FP32_NATIVE.md). Whole-frame FP32 remains unsupported;
the native experiment validates a hybrid certificate with CPU recovery instead.

## Decision

Do not enable automatic direct-FP32 Mandelbrot rendering based on viewport scale
or coordinate ULP spacing alone. The current accepted production FP32 envelope
is empty. Even the ordinary overview fails the proposed numeric screen at the
application's 300-iteration baseline. Keep calculation on the existing
`PrecisionSelectingRenderBackend` until GPU conformance and recovery are proven.

This is a completed **CPU numeric screening experiment**, not validation of a
native Mandelbrot shader. Java `float` models reject a simple implementation;
they cannot certify a MoltenVK shader, its compiler, or its floating-point modes.
That verification-only native spike and the optional production backend/CPU
recovery integration are complete; end-to-end performance is the next gate.

## Proposed numeric contract

The initial screen requires, for every checked pixel:

- Coordinate error no greater than 1/16 of a pixel on either axis; no adjacent
  coordinates collapsing to the same FP32 value.
- Exact agreement with the current CPU `escaped` flag, including the existing
  convention that escape on the final allowed iteration is reported as unescaped.
- For two escaped samples, iteration-count difference at most one and absolute
  `smoothIterations()` difference at most 0.01; all compared smooth values finite.

The coordinate and smooth limits are conservative **proposed engineering
tolerances**, not a proven universal quality bound. They screen the numeric
samples before palette selection. They do not guarantee a one-level RGB bound
for arbitrary palette stops/scales, histogram bins, or distance estimates. Those
features need their own conformance checks before claiming parity. Unescaped
final orbit components are not compared because the CPU interior shortcut may
return zero; iteration budgets and escaped flags still match the CPU semantics.

A sample with the wrong escaped classification must not be accepted merely
because most pixels in the frame pass. A passing scene is a test fixture, not a
proof that other scenes with the same scale and iteration budget will pass.

## Reproduce

```sh
mvn -Pfp32-precision -DskipTests integration-test
mvn -Dtest=MandelbrotFloatPrecisionBenchmarkTest test
```

The first command writes `target/mandelbrot-fp32.csv`. Numerical rejection is an
expected experiment result recorded as `screen_pass=false`, not a failed Maven
execution. No native libraries, JavaFX window, or production GPU selection are
used. The second command checks the experiment's CPU escape convention,
coordinate-rounding counterexamples, and conjugate/panned coordinate model.
The full portable suite passed after adding the experiment: 303 tests, zero
failures/errors, two native-gate skips.

Saved results: [GPU_FP32_PRECISION_RESULTS.csv](GPU_FP32_PRECISION_RESULTS.csv).
Recorded 2026-09-03 on Apple M3 Pro, Java 26.0.2, macOS 26.6.2 ARM64.

## Workload and models

Seven fixed scenes cover exterior/interior controls, the ordinary overview,
the cardioid cusp, the existing seahorse center at scales 0.024 and 0.00024,
and coordinate collapse at scale 1e-7. Each runs at 32, 64, 300 and 1000
iterations, with four numerical models:

| CSV mode | Coordinates | Orbit arithmetic |
| --- | --- | --- |
| `COORDINATES_ONLY` | CPU pixel coordinates rounded once to FP32 | Existing double CPU formula |
| `FLOAT_PER_PIXEL` | CPU pixel coordinates rounded once to FP32 | Java float, separate operations |
| `FLOAT_GRID` | Float origin + float index × float step, conjugate rows retained | Java float, separate operations |
| `FLOAT_FMA` | CPU pixel coordinates rounded once to FP32 | Float with explicit FMA in two recurrence expressions |

FP32 models omit cardioid/bulb shortcuts to isolate recurrence errors. Adding
float interior shortcuts requires additional false-interior checks. The FMA
variant explores one contraction pattern; it does not bound all shader results.
The coordinate-only model distinguishes information already lost before any
orbit calculation from error introduced by float arithmetic.

Small 384×256 frames are exhaustive (98,304 pixels each). The 3024×1964 Retina
grids use a deterministic 129×97 lattice (12,513 pixels) spanning both endpoints
of each axis; those results are sampled, not full-frame validation. Adjacent
coordinate collapse is checked across every row/column coordinate in both sizes.
The CSV retains viewport, dimensions, mode, limit, counts, maximum errors and
the first failing sampled pixel. There are 224 cases and 12,411,504 comparisons;
these include repeated points across models and iteration limits.

## Results

Selected 300-iteration rows; smooth failures count only pixels where both paths
report escape, so they are separate from escaped-flag mismatches:

| Scene / size | Model | Compared pixels | Wrong escaped flag | Smooth error > 0.01 |
| --- | --- | ---: | ---: | ---: |
| Overview / 384×256 | Coordinates only | 98,304 | 24 | 370 |
| Overview / 384×256 | Float per pixel | 98,304 | 28 | 422 |
| Overview / 384×256 | Float grid | 98,304 | 40 | 580 |
| Overview / 384×256 | Float FMA | 98,304 | 24 | 422 |
| Overview / 3024×1964 | Float per pixel | 12,513 | 2 | 44 |
| Seahorse 100× / 3024×1964 | Float per pixel | 12,513 | 70 | 535 |
| Seahorse 10,000× / 3024×1964 | Float per pixel | 12,513 | 716 | 6,173 |

The overview's maximum input-rounding error is only 0.0000126 pixel at 384×256,
yet its coordinate-only model changes escaped classifications. Thus passing
the coordinate screen cannot establish orbit correctness. Reducing the limit
to 32 still leaves four smooth-tolerance failures in the exhaustive overview's
float-per-pixel model. FMA does not eliminate the overview failures either.

At scale 0.00024 on the Retina grid, rounded-input error reaches 0.236 pixel,
already exceeding the coordinate criterion. At scale 1e-7, 4,977 of the 4,986
adjacent axis-coordinate pairs collapse in the rounded-input model. Such grids
must be excluded before dispatch, independent of iteration results.

Exterior/interior controls pass all tested budgets and models. Some short-orbit
scene cases also pass, but they do not establish a usable general navigation
envelope. Overall 90 of 224 cases pass this screen.

## Contract for the next native spike

1. Add an opt-in, verification-only Mandelbrot shader probe. Read iteration,
   escaped flag and final orbit values back, and apply this screen against the
   existing double CPU path. Test full Retina frames, boundary sweeps, panned
   grids and changed iteration limits; record actual shader/compiler/device
   behavior rather than inferring it from these Java models.
2. Before production acceptance, provide a conservative per-sample numerical
   error bound/reliability test with CPU recovery, or validate a more accurate
   GPU arithmetic method. An empirical iteration cutoff alone is insufficient.
   Until that exists, CPU comparison remains necessary and the probe establishes
   conformance only, not a speedup of the application.
3. Route unsupported grids/formulas/features and numerical/native failures to
   the existing precision-selecting CPU backend, using the original precise job.
   Never reconstruct fallback coordinates from rounded shader inputs. Retain
   generation cancellation and publish only validated or recovered samples;
   keep approximate data out of exact frame reuse and caches.

The native arithmetic validation and rejection contract are now documented in
`GPU_FP32_NATIVE.md`. CPU recovery, bounded asynchronous readback and progressive
publication are now integrated behind an opt-in flag. CPU remains the default,
and there is no GPU deep-zoom mode.
