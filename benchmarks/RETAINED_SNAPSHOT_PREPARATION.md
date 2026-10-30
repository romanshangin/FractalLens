# P1.2 retained-snapshot preparation experiment

> Historical evidence: results and “current source” describe the dated experiment, not validation of this checkout. Only [selected evidence](README.md) is included; “private archive” entries are unavailable here. Original SHAs/commands may require private revisions or inputs. See [provenance and replay limits](../docs/development/PUBLICATION.md).

**Reject** on-demand tile coloring; production stays control. Joint first-tile/
full-AA gate failed; no performance gain claimed.

## Candidate and fixed gate

Control `main` at `1ef60e0a0611ece75f6b7b3baa1c5a85899a9003`; candidate diff
SHA-256 `ca86cbd6372189c8048191363ce15461899304d721fe448a36869c7d4998d65c`.
Sorted retained snapshot by pixel, replaced full-frame retained ARGB preparation
with on-demand 32x32 tile coloring. Base array/mask/admission/generation guards
stay. Test compares tile/full colors across rows/palettes and snapshot after cache clear.

Predeclared 9.1 headless gate: exact sample/ARGB, retained-Julia first tile ≥10%
faster, full AA ≤5% regression, fresh Mandelbrot no-regression control. Retain
only if both Julia gates and fresh control pass.

## Environment and method

Measurement date: 2026-09-24. Apple Silicon macOS 27.0, 12 logical processors,
Java 26.0.1, JavaFX 26.0.2, 2 GiB maximum heap, 11 AA workers, CPU backend,
128 MiB AA cache. AC power was observed immediately before and after the
confirmation run. Thermal pressure during runs and physical scanout were not
measured.

Control archived exact `main`/unchanged build scripts; candidate working-tree classes.
Same control tests, `9.1-v1`, dependencies/JDK. Headless `BaselineBenchmark`:
`julia-aa-retained`, `mandelbrot-aa-regular`, 1512x982/regular/no profiling. Retained
prepares first AA untimed, measures second. Each JVM: one cold/three warmups/one
sample; 30 fresh alternating AB/BA pairs, never concurrent. Exact hashes/heap/GC:
[CSV](RETAINED_SNAPSHOT_PREPARATION_SAMPLES.csv).

The table reports medians across 30 measured pairs. Ratios are medians of
matched B/A samples; 95% intervals resample the 30 process pairs 10,000 times
with seed 20260924. These are descriptive intervals for this host and workload.

| Fixture and metric | Control A, ms | Candidate B, ms | Paired B/A | 95% interval |
| --- | ---: | ---: | ---: | ---: |
| Retained Julia, first AA tile | 10.584 | 8.962 | 0.855 | 0.767–0.994 |
| Retained Julia, full AA | 23.257 | 26.582 | 1.123 | 1.050–1.175 |
| Fresh Mandelbrot, first AA tile | 2.084 | 2.396 | 1.088 | 1.001–1.233 |
| Fresh Mandelbrot, full AA | 43.609 | 44.721 | 1.020 | 0.991–1.119 |

All 30 sample/ARGB pairs match per fixture. Retained full AA slower in 24/30;
interval crosses 1.05 slightly → inconclusive. First tile crosses 0.90; fresh
first-tile median regresses/no-regression unproven. CSV heap/GC is not retention/
allocation rate. Joint gate fails; remove production candidate, no FX/sustained
memory/thermal follow-up.

Before the campaign, `mvn -q -Dtest=AntialiasSampleCacheTest,InteractiveAntialiasServiceTest test`
passed with the candidate. After rejection, the candidate test was also
removed. The final repository contains only this experiment record and the
roadmap decision; the portable suite is not needed for documentation-only
changes.
