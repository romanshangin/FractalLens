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

## Exact periodicity experiment

An exact checkpoint-based periodicity check was evaluated for both formulas.
It compared orbit states only with exact floating-point equality, making the
classification correctness-preserving, but repeated states were too rare to
offset the additional checks in every iteration.

The Mandelbrot 100x scenario regressed from 365.22 ms to 375.35 ms (about 2.8%)
and the remaining changes were within run-to-run noise. The production change
was therefore rejected. A dense Julia reference-grid comparison was retained
to protect future loop-level optimizations.

## Cached orbit-square experiment

Manually carrying `zr²` and `zi²` between loop iterations was bit-for-bit
equivalent but did not improve Mandelbrot and slowed the measured Julia cases
by roughly 4-5%. HotSpot already performs the useful common-subexpression work,
while the additional live variables increase register pressure. The production
change was rejected.

## `FractalSample` allocation-cost experiment

The formula benchmark was recorded with Java Flight Recorder at 960x540, using
two warmups and five measured runs per scenario. The same workload was then
repeated with HotSpot escape analysis disabled via `-XX:-DoEscapeAnalysis`.

With normal escape analysis, JFR attributed 44 allocation samples and about
39 MB of sampled allocation weight to `FractalSample`. Disabling escape
analysis increased this to 538 samples and about 933 MB: roughly 24 times the
sampled allocation weight. This confirms that HotSpot scalar-replaces most of
the per-pixel result records in the current calculation path.

The timing effect depends on the amount of orbit work per pixel:

| Scenario | Normal p50 | No-EA p50 | Change |
| --- | ---: | ---: | ---: |
| Mandelbrot 1x | 18.41 ms | 19.86 ms | +7.9% |
| Mandelbrot 100x | 91.33 ms | 94.67 ms | +3.7% |
| Mandelbrot 10000x | 214.89 ms | 218.15 ms | +1.5% |
| Julia 1x | 38.96 ms | 46.33 ms | +18.9% |
| Julia 100x | 373.69 ms | 374.47 ms | +0.2% |
| Julia 10000x | 383.17 ms | 383.35 ms | +0.05% |

Allocation overhead is measurable for inexpensive overview pixels, especially
Julia 1x, but becomes negligible when iteration cost dominates. A mutable
output parameter or a formula-to-`FractalData` API would spread coupling and
complexity through the calculation boundary for little benefit in the slowest
scenarios. The value-returning `FractalSample` API is therefore retained.

## Mandelbrot conjugate-symmetry optimization

Mandelbrot samples are identical for conjugate coordinates, so an empty frame
whose render grid is centered around the real axis now calculates only its top
half. Each completed row span is copied to its conjugate row before both
regions are marked ready for progressive display. The optimization is guarded
by a formula capability, an ULP-bounded grid-symmetry check, and an empty
validity mask. Shifted views, Julia, and partially reused frames retain the
normal tile path.

The parallel render path was benchmarked at 1920x1080 with three warmups. Both
variants used the same delegated formula; only its symmetry capability was
toggled.

| Maximum iterations | Full-frame p50 | Symmetric p50 | Speedup |
| ---: | ---: | ---: | ---: |
| 300 | 10.80 ms | 5.76 ms | 1.88x |
| 3000 | 35.84 ms | 20.88 ms | 1.72x |

A centered render grid canonicalizes its lower-half imaginary coordinates as
the exact negation of the corresponding upper-half coordinates. A dense
401x301 comparison against the full calculation therefore verifies identical
iteration counts, escape flags, and smooth-iteration values. The standalone
benchmark can be repeated with `mvn -Psymmetry-benchmark verify -DskipTests`.

## Additional analytic interior shortcuts

Higher-period hyperbolic components were evaluated as candidates for static
interior rejection. Unlike the main component and period-2 component, their
boundaries are not a cardioid and an exact circle with comparably inexpensive
membership predicates. Period-3 boundaries already require a higher-degree
parametric description.

Approximate circles commonly drawn inside higher-period bulbs are unsuitable
as general membership tests: an overestimated radius produces false interior
pixels, while rigorously conservative inscribed disks cover relatively small
areas and add checks to every Mandelbrot sample. They also do not cover the
current 100x and 10000x benchmark views around Seahorse Valley sufficiently to
offset that constant overhead.

No additional static interior predicate was therefore added. The exact main
cardioid and period-2 bulb rejection remains the production fast path; more
general interior detection would require an orbit-based technique, whose exact
checkpoint variant has already been benchmarked and rejected above.

## Java Vector API experiment

A standalone Mandelbrot kernel prototype compared the scalar loop with a
masked `DoubleVector` loop on the same 960x540 inputs. Both implementations
included the cardioid and period-2 bulb checks. Two warmups and five measured
runs were used, and the final iteration counts, escape flags, and orbit values
were verified bit-for-bit.

On the current Apple ARM runtime, JDK 26 selected a 128-bit preferred double
species, providing only two lanes:

| Scenario | Scalar p50 | Vector p50 | Scalar / Vector |
| --- | ---: | ---: | ---: |
| Mandelbrot 1x | 9.53 ms | 17.37 ms | 0.55x |
| Mandelbrot 100x | 84.80 ms | 85.37 ms | 0.99x |
| Mandelbrot 10000x | 205.05 ms | 190.73 ms | 1.08x |

The masked vector loop regresses the inexpensive overview substantially,
reaches parity at 100x, and gains only about 8% in the iteration-heavy 10000x
case. Two-lane parallelism is insufficient to offset mask management, lane
divergence, and result extraction. Integrating the prototype would also add a
dependency on the incubating `jdk.incubator.vector` module and require a
separate production calculation path.

The scalar kernel is therefore retained. This decision can be revisited when
the Vector API is stable or the target runtime exposes wider double species.

## Tile-size and worker-count retuning

The parallel renderer was benchmarked again after the formula, symmetry, and
partial-frame optimizations. The sweep used a 1920x1080 frame on a 12-processor
runtime, two warmups, five measured runs, tile sizes 16/32/64, and worker counts
6/11/12. It measured both the first completed progressive region and total
frame time.

Representative p50 results:

| Scenario | Tile | Workers | First region | Total |
| --- | ---: | ---: | ---: | ---: |
| Mandelbrot overview | 32 | 11 | 0.29 ms | 12.05 ms |
| Mandelbrot overview | 64 | 11 | 0.16 ms | 8.39 ms |
| Mandelbrot 10000x | 32 | 11 | 0.76 ms | 105.66 ms |
| Mandelbrot 10000x | 64 | 11 | 2.14 ms | 106.18 ms |
| Julia 10000x | 32 | 11 | 0.96 ms | 184.75 ms |
| Julia 10000x | 64 | 11 | 3.06 ms | 186.38 ms |

Sixteen-pixel tiles consistently add scheduling overhead. Sixty-four-pixel
tiles improve the inexpensive Mandelbrot overview but delay the first deep
region by roughly 2-3 times and provide no deep-render throughput advantage.
Thirty-two-pixel tiles remain the best balanced default.

Using all 12 processors instead of 11 improved the measured deep total time by
only about 3-5%. The application keeps `availableProcessors() - 1` workers so
the JavaFX application thread and system retain scheduling headroom during a
long deep render. The benchmark can be repeated with
`mvn -Prender-tuning-benchmark verify -DskipTests`.

## Specialized Julia pipeline experiment

A direct Julia pipeline was compared with the generic `FractalFormula` and
`FractalSample` path. The specialized prototype inlined the Julia constant and
orbit loop and wrote primitive iteration, smooth-iteration, and escape values
directly into structure-of-arrays storage. The benchmark used 960x540 inputs,
two warmups, five measured runs, and alternated execution order to reduce
ordering bias.

| Scenario | Generic p50 | Direct p50 | Generic / Direct |
| --- | ---: | ---: | ---: |
| Julia 1x | 38.05 ms | 37.59 ms | 1.01x |
| Julia 100x | 362.05 ms | 359.67 ms | 1.01x |
| Julia 10000x | 370.90 ms | 368.08 ms | 1.01x |

Iteration counts, escape flags, and smooth-iteration values matched bit for
bit. The approximately 1% difference is too small to distinguish from normal
run-to-run variation and confirms the earlier allocation experiment: HotSpot
already inlines the formula call and scalar-replaces the short-lived sample in
the hot path.

The generic pipeline is retained to avoid duplicating calculation, cancellation,
partial-frame, and correctness logic. The experiment can be repeated with
`mvn -Pjulia-pipeline-benchmark verify -DskipTests`.

## Perturbation and reference-orbit investigation

Perturbation represents each pixel orbit as a delta from one shared reference
orbit. For a reference `Z(n + 1) = Z(n)^2 + C` and pixel offset `c`, the delta
recurrence is `z(n + 1) = 2 Z(n) z(n) + z(n)^2 + c`. This allows the reference
to use arbitrary precision while most per-pixel work remains in hardware
floating point.

A double-only prototype compared this recurrence with direct Mandelbrot
iteration at 480x270, using one warmup and three measured runs:

| Zoom | Direct p50 | Perturbation p50 | Direct / perturbation | Iteration mismatches |
| ---: | ---: | ---: | ---: | ---: |
| 10000x | 54.36 ms | 96.27 ms | 0.56x | 595 |
| 1000000x | 266.76 ms | 481.26 ms | 0.55x | 820 |
| 100000000x | 328.48 ms | 599.34 ms | 0.55x | 0 |

With both paths limited to doubles, perturbation adds arithmetic without
removing any expensive operation and is about 1.8 times slower. The mismatches
at the two shallower scales occur near sensitive boundaries because direct and
delta coordinate construction use different floating-point operation orders;
they demonstrate why a production implementation cannot treat a naive delta
recurrence as an exact replacement.

Perturbation becomes useful only after the viewport can represent coordinates
beyond double precision and calculate a high-precision reference orbit. That
implementation must also detect unreliable deltas and rebase them to another
reference orbit. Reference caching and series approximation can then reduce
the amortized cost further. Production integration is therefore deferred to
the deep-zoom phase; the current scalar kernel remains unchanged. The prototype
can be repeated with `mvn -Pperturbation-benchmark verify -DskipTests`.

## Deep-zoom correctness and performance gate

Completed on 2026-09-01 on the local arm64 Java 26.0.1 runtime. `mvn test`
completed successfully with 254 tests. The deep-zoom coverage verifies the
Mandelbrot perturbation output against arbitrary-precision control samples,
including a known boundary viewport, deep subpixel positions, a forced
additional-reference (glitch) path, long-running interior points, and a render
grid whose pixels cannot be represented by `double`. It also checks pan/zoom
tile ordering, cancellation before publication and while tiles are queued, and
the bounded exact-match reference-orbit cache.

The dedicated profile was run at 480x270, 964 maximum iterations, one warmup,
and three measured runs. The production deep scene was all-escaping at the
iteration cap, so it isolates backend startup and tile delivery rather than
claiming a general throughput gain:

| Run | Total | Reference orbit | Coordinate setup | First region | Cached reference |
| --- | ---: | ---: | ---: | ---: | ---: |
| Cold | 175.22 ms | 15.64 ms | 2.57 ms | 49.34 ms | 1 / 15,440 B |
| Cached repeat | 98.21 ms | 1.87 ms | 1.18 ms | 3.57 ms | 1 / 15,440 B |

The cold run completed 135 tiles with a 8.14 ms median tile time; the cached
repeat's median was 7.36 ms. No high-precision pixel fallbacks or additional
references were needed for that benchmark scene. The selector chose
`DirectDoubleRenderBackend` for a conventional viewport and
`MandelbrotPerturbationRenderBackend` once the grid exceeded the double-
precision capability. Numbers are machine-dependent; rerun the profile for a
performance decision on a different runtime.
