# Formula calculation baseline

Measured on 2026-08-27 with Java 26.0.1 on arm64. The benchmark is
single-threaded and uses one warm-up followed by three measured runs. Adaptive
iteration limits match the application policy: 300 base iterations plus 50 per
binary zoom level.

## 1920x1080

| Formula | Zoom | Max iterations | p50 | p95 | ns/pixel | Mpx/s | Mean iterations | Iteration p50 | Iteration p95 | Interior |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Mandelbrot | 1x | 300 | 271.29 ms | 274.54 ms | 130.83 | 7.64 | 48.28 | 3 | 300 | 14.82% |
| Mandelbrot | 100x | 632 | 1503.33 ms | 1566.19 ms | 724.99 | 1.38 | 284.06 | 72 | 632 | 39.08% |
| Mandelbrot | 10000x | 964 | 853.57 ms | 857.91 ms | 411.64 | 2.43 | 156.10 | 117 | 372 | 0.04% |
| Julia | 1x | 300 | 156.04 ms | 156.13 ms | 75.25 | 13.29 | 22.61 | 2 | 169 | 2.22% |
| Julia | 100x | 632 | 1494.65 ms | 1503.44 ms | 720.80 | 1.39 | 272.57 | 233 | 591 | 3.88% |
| Julia | 10000x | 964 | 1528.28 ms | 1532.17 ms | 737.02 | 1.36 | 283.11 | 241 | 518 | 0.31% |

## 2560x1440 HiDPI equivalent

| Formula | Zoom | Max iterations | p50 | p95 | ns/pixel | Mpx/s | Mean iterations | Iteration p50 | Iteration p95 | Interior |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Mandelbrot | 1x | 300 | 495.47 ms | 496.69 ms | 134.41 | 7.44 | 48.29 | 3 | 300 | 14.82% |
| Mandelbrot | 100x | 632 | 2660.60 ms | 2666.02 ms | 721.74 | 1.39 | 284.02 | 72 | 632 | 39.06% |
| Mandelbrot | 10000x | 964 | 1534.42 ms | 1541.89 ms | 416.24 | 2.40 | 156.06 | 117 | 372 | 0.04% |
| Julia | 1x | 300 | 276.32 ms | 276.94 ms | 74.96 | 13.34 | 22.60 | 2 | 169 | 2.20% |
| Julia | 100x | 632 | 2720.25 ms | 2863.67 ms | 737.91 | 1.36 | 272.57 | 233 | 590 | 3.86% |
| Julia | 10000x | 964 | 2782.38 ms | 2785.88 ms | 754.77 | 1.32 | 283.15 | 241 | 518 | 0.31% |

## Baseline observations

- Runtime scales almost linearly with pixel count: ns/pixel stays stable between
  1080p and the HiDPI-equivalent resolution.
- Mandelbrot overview spends most pixels outside the set, but 14.8% reach the
  maximum iteration count. Analytic interior rejection should strongly improve
  this case and the 100x case, where 39% of pixels reach the limit.
- The selected Mandelbrot 10000x region contains almost no interior pixels, so
  cardioid/bulb rejection should have little effect there. It is a useful guard
  against overfitting the optimization to overview images.
- Julia 100x and 10000x have high mean iteration counts despite low interior
  percentages. They are the primary scenarios for evaluating periodicity checks
  and loop-level optimizations that apply to both formulas.

## Main-cardioid and period-2-bulb rejection

After adding analytic rejection for the two largest known Mandelbrot interior
regions, the 1920x1080 benchmark produced:

| Scenario | Baseline p50 | Optimized p50 | Speedup |
|---|---:|---:|---:|
| Mandelbrot 1x | 271.29 ms | 73.70 ms | 3.68x |
| Mandelbrot 100x | 1503.33 ms | 365.22 ms | 4.12x |
| Mandelbrot 10000x | 853.57 ms | 863.12 ms | 0.99x |

The result follows the measured interior distribution: overview and 100x spend
substantial time on analytically recognizable interior points, while the chosen
10000x region contains almost none. Julia is unchanged and continues to serve
as a control group.

Correctness is checked against the original iterative algorithm over a dense
401x301 overview grid. Iteration counts, escape flags, and smooth-iteration
values remain identical for every sampled point.
