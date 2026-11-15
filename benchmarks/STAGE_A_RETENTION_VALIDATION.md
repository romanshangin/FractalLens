# P2.7 Stage A retained-frame validation

**Status, 2026-11-14: Stage A accepted for the tested macOS scope.**
The owner selected 320 MiB as the production default after the PR #70 rerun.
At 3024x1964 the production default returned to a retained scene in a median
of 79.9 ms. The first Fire recolor after restoring a frame with AA colors but
without its supersamples took 724 ms in that run. The owner accepted this
trade-off. Windows and Linux desktop validation remains pending and will run
separately.

The initial experiments below used the merged PR #68 tree (`d4c2b7f`) and
its 192 MiB production default.

## Environment and method

Apple M3 Pro, 18 GiB RAM, macOS 27.2.1 arm64, JDK 26.0.1 and JavaFX 26.0.2,
AC power. Production CPU renderer, Metal Prism confirmed in the launch log,
2x output scale and a 4 GiB Java heap limit. A private test driver used a
visible JavaFX window and invoked the production `FractalView.setFractal`
action. The driver waited for the requested scene to complete, the loading
screen to release the surface and the next scene post-layout callback. The
metric starts at entry to the switch action and ends at that callback. It
covers software presentation scheduling. It excludes native menu dispatch
and panel scanout.

Each trial first switched to the other fractal and waited for completion.
The measured return then used either the untouched cache or the same build
with its completed-frame cache cleared immediately before the return. Each
process discarded one warmup per condition and ran five alternating pairs.
The first condition alternated across three separate JVMs per fixture. Every
return compared the full target ARGB buffer and scene against the initial
render. The driver used the production render diagnostics and AA profile
counters to detect recalculation. The predeclared diagnostic target for a
1696x1188 hit was a median of at most 150 ms and at most half the cleared-cache
median, with zero base worker tasks and zero candidate detection or
supersampling when AA was selected.

Standard cases returned to the Mandelbrot home view (Ice palette, Refined,
301 iterations at 1696x1188 and 300 at 3024x1964) from Julia. The deep
Mandelbrot case used center `(0, 1)`, span `1e-30`, a fixed limit of 137
iterations and the default Base only deep mode. Wide Preview was disabled for
the paired comparisons. The deep Base only comparison reads base-worker
counters. The AA service can still report the last Julia pass in that case.

## Default cache

Medians and observed ranges in milliseconds, 15 measured returns per
condition. Cache occupancy is the frame cache's accounted size immediately
before the return.

| Target | Normal cache | Cache cleared | Normal cache occupancy | Work on normal return |
| --- | ---: | ---: | --- | --- |
| 1696x1188 standard | 39.6 (33.9–49.7) | 263.9 (251.4–288.9) | 2 frames, 189.5 MiB | 0 base tasks, AA candidates tested and samples |
| 1696x1188 deep Mandelbrot, Base only | 40.6 (34.4–50.7) | 215.0 (206.8–340.2) | 2 frames, 165.6 MiB | 0 base tasks |
| 3024x1964 standard | 715.7 (696.8–780.3) | 705.6 (679.8–785.3) | 1 frame, 152.9 MiB | 2,945 base tasks, 2,629,344 AA samples |

The 3024x1964 view produced identical pixels on the miss. The process peak
RSS ranges for these mixed hit/miss runs were 1,987–2,309 MiB (standard small),
1,790–2,216 MiB (deep small), and 2,645–2,713 MiB (standard large).

## Large-window budget diagnostic

The test driver applied either 192 or 320 MiB before opening the view, using
the same production build and 3024x1964 fixture. Six separate JVM starts
alternated the budgets (AB, BA, AB), with one discarded return and seven
measured returns per process. No trial clears the cache in this diagnostic.
Every return matched the original full ARGB buffer and scene.

| Test cap | Post-layout median (range), ms | Accounted cache before return | Kernel peak RSS, MiB |
| --- | ---: | --- | --- |
| 192 MiB | 727.4 (699.5–773.5) | 1 frame, 152.9 MiB | 2,283–3,028 |
| 320 MiB | 83.8 (72.5–104.8) | 2 frames, 305.9 MiB | 2,212–2,447 |

The larger cache hit incurred zero base tasks, AA candidate detection and AA
samples in all 21 measured returns. Peak RSS describes these processes, whose
allocations differed on cache hits and misses. The sustained memory impact of
holding more completed frames through varied workloads remains unmeasured.

## Additional checks

- With Wide Preview enabled at its default settings, a separate three-return
  smoke per cap at 3024x1964 gave medians of 753.5 and 88.3 ms. The larger cap
  again hit without base or AA work. Raw base-sample fingerprints, final ARGB
  and the scene matched the initial render on every return at both budgets.
- With deep Refined AA enabled, three measured Mandelbrot returns at
  1696x1188 matched the original ARGB without base or AA work (40.1 ms median).
  A separate deep Julia smoke at center `(2, 0)`, span `1e-35`, 137 iterations
  and 1696x1188 matched the original raw samples and ARGB. It also did no base
  or AA work (47.0 ms median). The Julia fixture ran the AA candidate pass on
  its initial render but produced no supersamples.
- `./mvnw clean test` passed on the tested Mac (762 tests, 176 opt-in skips).
  The active-desktop run of `FractalSwitchFrameRetentionFxTest`,
  `FractalDestinationsFxTest` and `FractalResizeFxTest` passed its 62
  enabled cases, including all 10 retention cases. With
  `-Dfractal.fx.fullscreen=true`, `FractalResizeFxTest` then passed all 54
  cases, including the fullscreen checks. These tests exercise JavaFX surface
  output and native fullscreen transitions on the tested Mac.
- GitHub checks for PR #68 passed, including portable Linux and Windows
  suite coverage. Desktop switching, target publication and memory on
  Windows and Linux remain open.

## Sustained mixed-scene screening

A second experiment on the same build used a 3024x1964 Metal window and
default Wide Preview. Each JVM ran at least 40 seconds after the initial
frame, finishing the last whole round. Two process pairs ran in AB, BA order
with 192 and 320 MiB respectively. The workload mixed Mandelbrot, Julia and
Tricorn, deep Mandelbrot with AA, palette changes, Undo/Redo, reset and
window resizing. Timing excludes the first round. An AppKit observer
watched for active Space changes. Both desktop and sleep checks passed.
Two overlapping setup attempts were stopped and excluded. The measured
series started in a fresh output directory.

| Pair | Standard return, ms (192 / 320) | Kernel peak RSS, MiB (192 / 320) | Median heap after explicit GC, MiB (192 / 320) |
| --- | ---: | ---: | ---: |
| 1 | 717.5 / 68.7 | 2721 / 2938 | 339 / 418 |
| 2 | 710.0 / 64.1 | 2697 / 2723 | 339 / 418 |

Both pairs met this experiment's predeclared per-pair screening limits: a
median standard return at most 150 ms and half the control, peak RSS at
most 110% of control, and median post-GC heap no more than 160 MiB above
control. The observed increases were 8.0% and 1.0% for peak RSS and about
79 MiB for median post-GC heap. Each process stayed within its accounted
cap. Repeated switch and history actions matched sample and ARGB
fingerprints across budgets for the tested scenes and jobs.

At 320 MiB, 22 of 26 standard returns and all eight measured deep returns
used zero base work, AA candidate detection or supersamples. The four
remaining standard returns reused the base and redid AA following palette
changes. The deep Undo/Redo and switch actions also avoided recalculation.
The larger cap remained a test setting. Two process pairs provided limited
evidence for the memory tail, particularly with a faster process completing
more rounds in the same duration.

## Fixed-workload comparison with pan

The follow-up used the same Metal desktop, build and 3024x1964 target. Each
fresh JVM ran seven rounds, discarding the first for action timings. The
paired workload also zoomed before navigating by keyboard in all four
directions. Each pan had to change the viewport and publish a completed
frame. Comparisons used the complete base-sample and ARGB fingerprints at
matched action boundaries. An earlier run was excluded after its pan probe
was found to allow the original viewport.

Six AB/BA pairs completed. Standard-return medians were 700–721 ms with
192 MiB and 65–81 ms with 320 MiB. The median paired pan ratio was 0.982
(320/192) and the median paired peak RSS ratio was 0.868 (range 0.728–1.011).
Median heap after explicit GC increased by 79.3–79.6 MiB. Both budgets stayed
within their accounted caps. These timing and memory observations met the
predeclared screening limits on this workload.

**Historical decision for `d4c2b7f`: reject the 320 MiB promotion.** In all six
pairs, two recolor checkpoints differed and the other 44 checkpoints per
pair matched. After an exact
return under Ice, changing to Fire then back to Ice produced different
completed ARGB at the two caps with identical base samples. The driver
confirmed that the captured colors stayed stable across two subsequent
JavaFX pulses. The next switch and return matched the original Ice pixels.
At 320 MiB, the colors-only restore triggered a new Fire AA pass; the
following Ice recolor reused samples from that pass. Candidate selection
uses palette-dependent color contrast. The result is consistent with
different sets of pixels being refined after recoloring.

## Deep Julia supersampling and menu actions

A separate 1696x1188, 192 MiB return check centered the Julia view on the
repelling fixed point of the default binary64 constant, at span `1e-35`
and a fixed 137 iterations with deep Refined AA. The initial frame computed
1,244,592 AA samples. After switching to Mandelbrot, all three measured
Julia returns matched the original sample fingerprint and ARGB buffer.
Each skipped base tasks, AA candidate detection and supersampling. Median
post-layout latency was 50.8 ms.

Both targeted `MainMenuBarFxTest` cases passed. They exercised switching to
remembered views and Undo/Redo, comparing the restored pixels and menu state.
These tests invoked menu actions programmatically.

The current source build was also launched through the macOS development
launcher on the tested Metal desktop with a temporary application profile.
The native Fractal menu opened Julia from Mandelbrot. Native Edit > Undo
returned to a visibly completed Mandelbrot, Edit > Redo completed the next
render, and the native Fractal menu returned to Mandelbrot again. The
walkthrough checks menu dispatch and completed presentation in a window,
with the exact history assertions covered by the automated tests above.

## Rerun after PR #70 (2026-11-14)

Repeated the fixed-workload comparison on `6cac4c6`, with the same
3024x1964 Metal workload, default Wide Preview, six AB/BA pairs and 46
measured checkpoints per pair. The earlier rejection applies to
`d4c2b7f`. On the updated build all 276 matched checkpoints had identical
base-sample and ARGB fingerprints, including Fire and Ice recolors.

| Metric | Result |
| --- | --- |
| Standard return, process medians | 732–764 ms at 192 MiB; 69–77 ms at 320 MiB |
| Standard returns with zero base or AA work at 320 MiB | 30 of 36 |
| Pan, median paired timing ratio (320/192) | 0.980 |
| Kernel peak RSS, median paired ratio (range) | 0.878 (0.746–1.168) |
| Median heap after explicit GC, paired increase | 78.9–79.2 MiB |
| Maximum observed cache charge | 177.52 MiB at 192 MiB; 316.57 MiB at 320 MiB |
| Fire recolor after the colors-only restore checkpoint | 691–775 ms at 320 MiB; 8–17 ms at 192 MiB |

This six-pair rerun used the policy declared for the fixed-workload comparison.
The peak RSS ratio (320 MiB / 192 MiB) had to be at most 1.10 for the median
across pairs and at most 1.20 in every pair. The two-pair sustained screening
above used a separate 1.10 limit for each pair. For this rerun, the median
ratio was 0.878 and the largest was 1.168 (pair 3), so both RSS limits passed.

The other predeclared limits also passed. Each pair's standard-return median
at 320 MiB was at most 150 ms and half of its control median. The median paired
pan ratio was at most 1.15, the post-GC median heap increase in each pair was
at most 160 MiB, and all observed cache charges stayed within their caps.
The Fire checkpoint at 320 MiB restored AA colors without their samples on the
preceding switch, then sampled AA again for the palette change. The policy
specified no limit for that recolor latency.

`./mvnw clean test` passed (780 discovered, 179 skipped). All 13
`FractalSwitchFrameRetentionFxTest` cases passed on the active macOS desktop.
The run used command-scoped sleep inhibition and monitored the active Space
and display. Its sleep assertions were removed at exit.

## macOS acceptance with the production default

Windows and Linux desktop validation will run separately from this macOS
acceptance.

The six-pair comparison used a test-only cap. After reviewing its results,
the owner selected 320 MiB as the production default and accepted the measured
cost of the first recolor after a colors-only restore. This changes only the
shared completed-frame cache limit.

The production-default build passed all eight targeted cache tests, the
portable suite (780 discovered, 179 skipped), and all 13 active-desktop
retention tests. A separate 3024x1964 Metal run used the production cap
without overriding it. All 46 measured checkpoints matched the prior
320 MiB run. The standard-return median was 79.9 ms, and five of six
returns performed zero base or AA work. Maximum accounted occupancy was
316.57 MiB. The measured Fire recolor after a colors-only restore took
724 ms. Stage A is accepted for the tested macOS scope.

The local test commands and campaigns used command-scoped `caffeinate -di`.
The active console and awake display were checked during graphical work.
The owned sleep assertions were gone after the runs. Raw logs, run scripts,
drivers and measurement artifacts are archived privately. The
[isolated scan run](RETAINED_RETURN_SCAN_RESULTS.md) retains its original
results and scope.
