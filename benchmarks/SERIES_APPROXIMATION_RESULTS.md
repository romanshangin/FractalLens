# Series approximation benchmark results

> Historical evidence: results and “current source” below refer to the dated
> experiment, not a new validation of this checkout. Only the
> [selected evidence](README.md) is included; entries marked “private archive”
> are unavailable in this repository. Original SHA values and recorded commands
> describe the original run and may require private revisions or inputs. See
> [provenance and replay limits](../PUBLICATION.md).

## Decision

Keep the current per-pixel perturbation recurrence. The tested cubic series
does not yet provide enough safe end-to-end gain to justify adding it to the
production deep-zoom backend.

## Method

`SeriesApproximationBenchmark` precomputes the first three complex polynomial
coefficients of the Mandelbrot perturbation recurrence and uses them to skip
16, 32, or 64 initial iterations. It compares the result with the existing
full perturbation recurrence at every pixel, including both escape iteration
and smooth iteration value.

The benchmark is isolated in the test source set and can be run with:

```shell
mvn verify -Pseries-approximation-benchmark -DskipTests
```

The validation run used 240x135 pixels, 964 maximum iterations, one warmup,
and three measured runs:

| Zoom | Skip | Speedup | Iteration mismatches | Smooth mismatches | Maximum smooth error |
|---:|---:|---:|---:|---:|---:|
| 1e8 | 16 | 1.012x | 0 | 0 | 1.501e-11 |
| 1e8 | 32 | 1.033x | 0 | 0 | 8.845e-11 |
| 1e8 | 64 | 1.065x | 0 | 3 | 9.796e-6 |
| 1e12 | 16 | 1.015x | 0 | 0 | 0 |
| 1e12 | 32 | 1.033x | 0 | 0 | 0 |
| 1e12 | 64 | 1.068x | 0 | 0 | 0 |
| 1e16 | 16 | 1.016x | 0 | 0 | 0 |
| 1e16 | 32 | 1.030x | 0 | 0 | 0 |
| 1e16 | 64 | 1.068x | 0 | 0 | 0 |

At the current `1e-6` smooth-value tolerance, skips of 16 and 32 are accurate
but save too little work. A skip of 64 is still modest and already exceeds the
tolerance at 1e8 zoom. A production attempt would therefore need an adaptive
validity bound, higher-order coefficients, and measurements inside the tiled
backend; this experiment does not justify that added complexity.
