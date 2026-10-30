# Series approximation benchmark results

> Historical evidence: results and “current source” describe the dated experiment, not validation of this checkout. Only [selected evidence](README.md) is included; “private archive” entries are unavailable here. Original SHAs/commands may require private revisions or inputs. See [provenance and replay limits](../docs/development/PUBLICATION.md).

## Decision

Retain per-pixel perturbation: tested cubic series lacks enough safe end-to-end
gain for production deep zoom.

## Method

`SeriesApproximationBenchmark` computes three complex polynomial coefficients,
skips 16/32/64 initial Mandelbrot iterations and compares every pixel's escape/
smooth iteration against full perturbation.

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

At `1e-6` smooth tolerance, 16/32 skips are accurate but save too little; 64
still gains little and exceeds tolerance at 1e8 zoom. Production would need
adaptive validity, higher order and tiled measurements; added complexity unjustified.
