# Mandelbrot FP32 precision screening (roadmap 8.3)

> Historical evidence: results and “current source” describe the dated experiment, not validation of this checkout. Only [selected evidence](README.md) is included; “private archive” entries are unavailable here. Original SHAs/commands may require private revisions or inputs. See [provenance and replay limits](../docs/development/PUBLICATION.md).

Initial CPU screen; [native contract](GPU_FP32_NATIVE.md) separately validates
hybrid certificate/recovery, not unsupported whole-frame FP32.

## Decision

No automatic direct FP32 from scale/ULP alone: accepted production envelope empty;
overview fails even at 300 iterations. Keep `PrecisionSelectingRenderBackend`
until GPU conformance/recovery are proven.

Completed **CPU numeric screen**, not native validation. Java `float` rejects
simple FP32, certifies no MoltenVK compiler/modes. Subsequent native spike and
optional production/recovery integration are complete; performance gate follows.

## Proposed numeric contract

The initial screen requires, for each checked pixel:

- Coordinate error no greater than 1/16 of a pixel on either axis; no adjacent
  coordinates collapsing to the same FP32 value.
- Exact agreement with the current CPU `escaped` flag, including the existing
  convention that escape on the final allowed iteration is reported as unescaped.
- For two escaped samples, iteration-count difference at most one and absolute
  `smoothIterations()` difference at most 0.01; all compared smooth values finite.

Coordinate/smooth limits are **proposed engineering tolerances**, not universal
quality bounds. Pre-palette screening guarantees no arbitrary-palette/scale RGB,
histogram or distance parity; test each. Do not compare unescaped final components
(CPU interior shortcuts may return zero); caps/escape semantics still match.

A sample with the wrong escaped classification must not be accepted merely
because most pixels in the frame pass. A passing scene is a test fixture, not a
proof that other scenes with the same scale and iteration budget will pass.

## Reproduce

```sh
mvn -Pfp32-precision -DskipTests integration-test
mvn -Dtest=MandelbrotFloatPrecisionBenchmarkTest test
```

First command writes `target/mandelbrot-fp32.csv`; expected `screen_pass=false`
is numeric rejection, not Maven failure. No native/FX/production GPU selection.
Second tests CPU escape, rounding counterexamples/conjugate-pan model.
Portable: 303 tests, no failures/errors, two native skips.

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

Models omit cardioid/bulb to isolate recurrence; float shortcuts need false-interior
tests. FMA explores one contraction, not all shaders. Coordinate-only separates
pre-orbit information loss from float arithmetic error.

384×256 is exhaustive (98,304 pixels); Retina 3024×1964 uses deterministic
129×97 endpoint lattice (12,513), not full validation. Check every adjacent axis
pair at both sizes. CSV: viewport/size/mode/cap/counts/max errors/first failed pixel.
224 cases/12,411,504 comparisons include repeats across models/caps.

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

Overview rounding only 0.0000126 pixel at 384×256 still changes escape classification:
coordinate pass is not orbit proof. Even 32 iterations leaves four smooth failures
in exhaustive float-per-pixel overview; FMA also fails.

Retina scale 0.00024 rounding =0.236 pixel, over limit. At 1e-7, 4,977/4,986
adjacent pairs collapse. Reject such grids pre-dispatch regardless of orbit results.

Exterior/interior controls pass all tested budgets and models. Some short-orbit scene
cases also pass, but they do not prove a usable general navigation envelope. Overall 90
of 224 cases pass this screen.

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
publication are now integrated behind an opt-in flag. CPU stays the default, and there
is no GPU deep-zoom mode.
