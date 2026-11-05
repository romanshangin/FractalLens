# Process-memory gate v2 (calibrated)

## Why the per-pair rule was replaced

The earlier wide-preview and AA memory controls required candidate/control peak
RSS <= 1.05 in **every** process pair. The command lines used `-Xmx4g` without a
fixed initial heap, and G1 expands committed heap at different points in each
run. In the nine-pair distance-only extension, control processes alone ranged
from 2.375 to 2.983 GB. Across distinct control processes,
P(ratio > 1.05) = 0.281. Two identical builds would therefore fail that rule
with probability of about 0.86 over 6 pairs and almost certainly over 30 pairs.
Earlier attribution found committed heap expanding toward 4 GiB and nearly
equal occupied heap after GC in both builds. Thus the old rule measured
heap-expansion timing, not a difference between builds.

The earlier decisions stay recorded as **v1 results** and are not relabelled:
the wide-preview reject (2026-11-04), the primitive-buffer reject and the
distance-only reject. v2 is a new experiment with fresh process pairs; no v1
process contributes to a v2 statistic.

## Declared protocol (2026-11-05, before any v2 process)

Everything except the memory rule is unchanged: JVM/JavaFX command lines,
`-Xmx4g` with no `-Xms` or GC flags, Metal Prism, CPU rendering, fixtures,
sizes, modes, three warmups, five samples, first sequences, drivers, exact
sample/ARGB controls, complete-matrix validators, per-group timing limit (upper
paired 95% B/A <= 1.05, seeded 5000-resample hierarchical bootstrap),
wide availability >= 95%, AB/BA alternation, thermal observations and frozen-build
hash checks before and after.

Memory statistic: kernel peak RSS per process, r_i = B_i / A_i per pair.

- **G1, central:** median r_i, with a seeded 5000-resample bootstrap over
  process pairs.
- **G2, tail:** nearest-rank P90(B) / P90(A), with the same bootstrap
  resampling whole pairs.
- Each gate passes if its upper 95% bound is <= 1.05, fails if its lower bound
  is > 1.05, and is otherwise inconclusive. Memory passes only if both gates pass.
- A process failure or an exact-control failure is a hard stop. There is no
  early stop on memory statistics.

**Calibration.** Before each candidate stage, the same command lines run with the
control build under both labels (A/A). Exact fingerprints must match. Observed
G1/G2 on A/A must not fail. A simulation (1000 draws of N pairs, random
orientation, 1000 resamples) estimates the false-fail and pass rates for
identical builds. The gate is valid only with a combined false-fail rate <= 0.05.
The AA stage uses N = 30, as its timing protocol requires. The wide stage uses
the smallest N in {6, 12, 18, 24} with pass rate >= 0.80; if none qualifies,
the wide stage is not run.

## Calibration results

| Workload | A/A pairs | G1 median ratio (95% interval) | G2 P90 ratio (95% interval) | Simulated false-fail | Simulated pass at N |
| --- | ---: | --- | --- | ---: | --- |
| AA FX (`BaselineFxBenchmark`) | 10 | 0.964 (0.930–1.005) pass | 0.956 (0.942–1.058) inconclusive | 0.000 | 0.527 at 30 |
| Wide input (`WidePreviewInputBenchmark`) | 6 | 0.996 (0.971–1.013) pass | 0.962 (0.961–1.015) pass | 0.000 | 1.000 at 6–24 |

Both gates are valid: identical builds never failed in simulation. The AA gate
has limited power: identical builds pass both parts only about half the time at
30 pairs, so an inconclusive AA memory result is expected without a real
difference. The wide workload runs close to the heap maximum, with RSS between
3.7 and 4.1 GB, and is much less variable. Its planned N was 6.

## AA distance-only candidate: inconclusive (not retained)

Control `182c799b9e4752b669d4fe01a697e59a4fa97b6ca065e4a1ca474d8c2c372500`,
candidate `40196cd0e66ce3d33475a2bbbeb07a59c2947a50c41bb7d176c755c9de3a8940`
(the distance-only build from [the AA report](AA_MEMORY_VALIDATION.md)). The
campaign used 30 fresh FX pairs with 150 matched samples per timing group. Its
allocation evidence reuses the six completed allocation pairs of the same frozen
builds and commands: Mandelbrot B/A 0.423/0.379 passed, and Julia passed.

| Control | Result |
| --- | --- |
| Exact sample/ARGB, complete matrices | pass for all 60 processes |
| Peak RSS, control median (range) | 2.793 GB (2.236–3.496) |
| Peak RSS, candidate median (range) | 2.874 GB (2.490–3.420) |
| G1 median ratio | 1.071 (0.990–1.084), inconclusive |
| G2 P90 ratio | 1.073 (0.954–1.125), inconclusive |
| Timing groups | 24 pass, 16 inconclusive, 0 fail (of 40) |
| Thermal pressure at process boundaries | nominal |

**Decision:** inconclusive. The candidate is not restored to production.
Several unchanged-code Julia groups were slightly slower: 1512x982 FAST `aa_ms`
1.042 (1.027–1.055) and `full_publish_ms` 1.043 (1.034–1.057). A possible cause
is a JIT effect of the added formula default method, but this is unverified.
The final tree keeps only the AA cache-budget correction.

## Wide preview (build 56730450, 128 MiB AA cache): inconclusive, memory passes

The control is `e4450ac3a6178b88269a351f70bb4a6c180153e8e4bb6304d208695e381ccacd`
(main plus the driver). The candidate is the default-disabled tree as frozen
on 2026-11-05, with the 128 MiB AA cache default, and the identical external driver, which enables `CANDIDATE` after source
conformance. It is not the current tree: the AA cache default was later
[recalculated to 512 MiB](AA_MEMORY_VALIDATION.md#follow-up-default-budget-recalculation).
Its frozen identity is
`567304500c12af3b7aa1f90308f2d43c6f3643e0a6eb6556a3c55dab338ffa8c`. Its sources
are identical to the cache-only build `a1ae3ded...`; only the rebuilt native menu
library binary differs. The run used 6 pairs and 30 matched samples per group.

| Control | Result |
| --- | --- |
| Exact sample/ARGB, complete matrices | pass for all 12 processes |
| Reserve ready / complete before gesture | 100% / 100% of measured sequences |
| Peak RSS, control range | 3.735–3.924 GB |
| Peak RSS, candidate range | 3.548–3.895 GB |
| G1 median ratio | 0.969 (0.925–1.031), **pass** |
| G2 P90 ratio | 0.992 (0.974–0.995), **pass** |
| Timing groups | 31 pass, 25 inconclusive, 0 fail (of 56) |
| Thermal pressure at process boundaries | nominal |

Every 3024x1964 pan group passed. For example, FAST last input to COMPLETE was
0.688 (0.533–0.796) and REFINED was 0.825 (0.627–0.896). 3024x1964 zoom-out
last input to COMPLETE also passed in both modes. Most 1512x982 pan groups and
the first-target/preview groups of zoom-out are inconclusive. Notably, 3024x1964
FAST zoom-out first input to preview after layout was 1.427 (0.925–2.008), and
last input to first target was 1.170 (0.897–1.976). A real regression in
early zoom-out publication is not excluded.

**Decision:** inconclusive. The memory blocker is resolved under v2, but
timing acceptance is incomplete. `WidePreviewSettings.DEFAULT` stays disabled,
and promotion needs a separately declared timing extension. That extension
should focus on early zoom-out publication. That extension, TE1, is recorded in
[the wide-preview report](WIDE_PREVIEW_VALIDATION.md#follow-up-timing-extension-te1).
On TE1's 24 fresh pairs, G1 passed but the G2 tail was inconclusive
(upper bound 1.0502), so the memory pass above holds for this six-pair stage only.

## Environment and limits

Apple M3 Pro, 12 logical CPUs, 18 GiB RAM, macOS 27.2 arm64, Oracle JDK 26.0.1,
JavaFX 26.0.2, Metal Prism, CPU calculation, GPU dispatch off, AC power. An
unrelated idle local background service was running; no other builds, tests or
measurements ran concurrently. Thermal state was observed only at process
boundaries.

Not covered: a global RSS ceiling, long-session soak, native GPU, Windows/Linux,
Intel/AMD Macs and native fullscreen. Raw commands, frozen builds, process
records and logs are kept in the private archive.
