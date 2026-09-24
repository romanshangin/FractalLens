# P1.2 retained-snapshot preparation experiment

Decision: **reject** the on-demand tile-coloring candidate. The production
implementation remains at the control behavior. The required first-tile and
full-AA gates did not both pass; no performance improvement is claimed.

## Candidate and fixed gate

Control source was `main` at `1ef60e0a0611ece75f6b7b3baa1c5a85899a9003`.
The uncommitted candidate diff had SHA-256
`ca86cbd6372189c8048191363ce15461899304d721fe448a36869c7d4998d65c`.
It sorted the immutable retained sample snapshot by frame pixel, removed the
full-frame retained ARGB preparation, and colored retained samples on demand
inside each 32x32 AA tile. The base-only color array, membership mask, worker
admission and generation checks remained in place. A targeted snapshot test
compared tile colors with full-snapshot colors across rows and palettes and
checked that an old snapshot stayed usable after cache clearing.

Before measurement, the candidate gate was exact sample/ARGB equality, a
first-tile improvement of at least 10% for retained Julia, and no more than a
5% full-AA regression. Fresh Mandelbrot AA was a no-regression control. The
candidate would be retained only if both retained-Julia timing gates and the
fresh-scene control passed. These limits follow the 9.1 paired comparison
policy; this experiment is scoped to the selected headless scenes.

## Environment and method

Measurement date: 2026-09-24. Apple Silicon macOS 27.0, 12 logical processors,
Java 26.0.1, JavaFX 26.0.2, 2 GiB maximum heap, 11 AA workers, CPU backend,
128 MiB AA cache. AC power was observed immediately before and after the
confirmation run. Thermal pressure during runs and physical scanout were not
measured.

The control was compiled from an archive of the exact `main` source with the
checkout's unchanged build scripts. Candidate production classes were compiled
from the working tree. Both used the same control test classes, fixture matrix
`9.1-v1`, dependency classpath and JDK. The headless `BaselineBenchmark` ran
`julia-aa-retained` and `mandelbrot-aa-regular` at 1512x982 render pixels,
regular sampling, with AA profiling disabled. The retained fixture performs
an untimed first refinement on the same frame before timing the second one.
Each JVM ran one cold sequence, three warmups and one measured sequence. There
were 30 fresh JVM pairs, alternating AB/BA order. The control and candidate
were never run concurrently. The raw measured rows, including exact hashes,
heap and GC observations, are in
[RETAINED_SNAPSHOT_PREPARATION_SAMPLES.csv](RETAINED_SNAPSHOT_PREPARATION_SAMPLES.csv).

The table reports medians across 30 measured pairs. Ratios are medians of
matched B/A samples; 95% intervals resample the 30 process pairs 10,000 times
with seed 20260924. These are descriptive intervals for this host and workload.

| Fixture and metric | Control A, ms | Candidate B, ms | Paired B/A | 95% interval |
| --- | ---: | ---: | ---: | ---: |
| Retained Julia, first AA tile | 10.584 | 8.962 | 0.855 | 0.767–0.994 |
| Retained Julia, full AA | 23.257 | 26.582 | 1.123 | 1.050–1.175 |
| Fresh Mandelbrot, first AA tile | 2.084 | 2.396 | 1.088 | 1.001–1.233 |
| Fresh Mandelbrot, full AA | 43.609 | 44.721 | 1.020 | 0.991–1.119 |

All 30 matched sample and ARGB hashes agree for each fixture. The retained
full-AA result was slower in 24 of 30 pairs. Its interval overlaps the 1.05
control limit slightly, so the strict full-AA gate is inconclusive rather than
a confirmed pass. The first-tile interval also crosses the required 0.90
improvement limit. The fresh first-tile control has a median regression, and
its interval does not establish the required no-regression gate. Heap-after and
GC samples are retained in the CSV; they do not establish retained memory or
allocation rates. The candidate was removed from production after this failed
joint gate. JavaFX publication and sustained memory/thermal checks were not run
for the rejected candidate.

Before the campaign, `mvn -q -Dtest=AntialiasSampleCacheTest,InteractiveAntialiasServiceTest test`
passed with the candidate. After rejection, the candidate test was also
removed. The final repository contains only this experiment record and the
roadmap decision; the portable suite is not needed for documentation-only
changes.
