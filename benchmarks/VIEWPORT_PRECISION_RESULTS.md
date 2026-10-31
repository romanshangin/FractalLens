# Viewport working-precision fix: performance effect

> Publication: raw logs, per-JVM samples, frame dumps, frozen builds and the
> generated inflated inputs stay in the private archive. This report, the
> [paired summary](VIEWPORT_PRECISION_SUMMARY.csv) and the
> [predeclared policy](policies/viewport-precision-pr51.json) are the selected
> evidence. See [provenance and replay limits](../docs/development/PUBLICATION.md).

Measured 2026-10-30. This is a new, separate experiment; it does not change any
earlier result.

## Summary

| Question | Decision | Evidence in one line |
| --- | --- | --- |
| 1. Cost of `GUARD_DIGITS` 20 → 30 on navigated deep views | **inconclusive** under the predeclared gate; no user-visible regression found | Reference orbit +4–12% (≤ 0.26 ms), first region +4–7% (≤ 0.53 ms); full frames within ±5% except Mandelbrot 1e-150 at +5.9% (95% interval 1.025–1.108, +16 ms of 336 ms) |
| 2a. Same previously inflated view restored on both builds | **hypothesis confirmed** (no change) | Render and camera arithmetic match within the setup's resolution; one 9,237-digit Go to is +0.27 ms on the candidate |
| 2b. The same Copy/Go to/save-restore workflow repeated 80 times | **retain**: camera/UI arithmetic improves, render does not change | Camera operations 80–1,000x faster, but they cost at most about 1 ms each on control; full frames 0.95–0.99 B/A |
| 3. Gesture-to-visible latency on an inflated view | **not measured** | See "Not measured" |

PR #51 is a correctness fix and is kept. It gives no meaningful rendering speedup.
The large camera-arithmetic ratios come from avoiding unbounded digit growth; in
absolute terms they save at most about 1 ms per operation after 80 cycles.
The guard increase costs a small amount, measurable in reference orbits and
first-region time, and this host's resolution of about ±5% does not exclude it
for full frames.

## Builds and isolation

- Control A: `bc26acd34660f85244f7773d5c6bede7ac1347f8` (main before PR #51).
- Candidate B: `91b22cfcc671a2a72afd699d45e305d170e76295` (merge of PR #51). Current
  `main` (`3926dff`, PR #52) changes only UI formatting and Go to dialog handling
  in `ui` and `src/main/macos`, not `Viewport`, `PreciseRenderGrid`,
  `FractalCamera` or rendering, so it was excluded to isolate PR #51.
- Both snapshots were frozen with `scripts/benchmarks/prepare_baseline_build.py
  --working-tree` from detached worktrees. Each uses its revision's `src/main` and
  the **same** test tree: candidate `src/test` plus
  `ViewportPrecisionBenchmark.java` (SHA-256
  `3fc4db7e2c6ecaab2ec97ad6f15a4cd8df232e9c7ffd1b4ce0840d6b5b57367d`). Build
  identities: A `7d1eebeee6abe7aa9ff10669e83298481d17efba9604852d77bef1cee7bfac3e`,
  B `1bd5209f9cc36b306912bbddc558ace8cc1b23de4cf97c6b49d5b299082afd2d`. The
  compiled snapshots differ only in `Viewport.class`, `PreciseRenderGrid.class`,
  `FractalCamera.class` and the rebuilt macOS menu dylib, which headless runs do not
  load. All `test-classes` and `lib` files are identical.
- `run_baseline_pairs.py` was not reusable as is. It accepts only the
  `BaselineBenchmark` driver and rejects driver changes between builds, but
  `BaselineFixtures` differs between the two revisions (`9.1-v2` → `9.1-v3`), and
  its derived fixtures come from each build's own `Viewport`. This campaign uses a
  dedicated driver instead, and both builds share one compiled copy of it.

## Environment

macOS 27.2, arm64, Apple M3 Pro (6 performance + 6 efficiency cores), 18 GiB
memory, Homebrew OpenJDK 26.0.2 for building and running, Maven 3.9.16 (the
wrapper's pinned version). Each JVM ran with `-Xmx4g`, NMT off, GPU dispatch
disabled, AA profiling and render diagnostics off, and a private temp directory.
AC power and `nominal` thermal pressure were recorded immediately before and after
every JVM. Thermal state during runs, physical temperature and energy were not
measured. Normal desktop applications, including a browser, kept running and were
not controlled; alternating order and matched pairs limit, but do not remove,
their influence.

## Method

`com.shangin.fractal.render.ViewportPrecisionBenchmark` (headless, test classpath)
renders at 1512x982 render pixels, the protocol's realistic window size, with the
adaptive iteration cap, `PrecisionSelectingRenderBackend` selection as in
production (direct double → Mandelbrot perturbation+BLA → Julia deep zoom), and
no orbit trap. Each workload is built by **the build's own** `Viewport` and
`FractalCamera` code from identical decimal inputs:

- `*-nav-D`: navigated deep view at nominal scale D. The target is placed off-centre
  at 8D through `withCalculatedValues`, followed by three wheel zoom-ins (0.5) and
  one pinch (0.9) at an off-centre point. Mandelbrot targets the Misiurewicz point
  `c = i`. Julia (`c = -0.8 + 0.156i`) targets the repelling fixed point used by
  `JuliaDeepZoomRenderBackendTest`.
- `mandelbrot-zoomout-1e-80`: the 1e-80 view, then two centre wheel zoom-outs (x2)
  rendered by the same cached backend.
- `*-restored-k80`: a shared input, generated once on control by 80 cycles of the
  workflow below starting from the 1e-30 navigated view (input SHA-256
  `347de8fe98ffd46703bde206552c217bdb82e201968c3452e97381ff2432708a`; centres of
  3,965–3,971 digits and a 4,031-digit scale). Regenerating it from the frozen
  control build reproduced it byte for byte, apart from the timestamp comment.
- `julia-reported-x`: the exact 9,237-digit view attached to the Julia pixelation
  report (`src/test/resources/julia/reported-pixelation.txt`), a real inflated view.
- `*-cycled-80x1e-30`: each build applies the same 80 cycles to its own 1e-30 view.
  One cycle is a 13x7-pixel drag, Copy (pre-PR #52 full-precision zoom text), Go to
  with that re-entered zoom (`FractalCamera.destination`), and a decimal
  save/restore (`new Viewport(String…)`).

Working precision of the measured views (`viewport` / `grid` digits, A → B):

| Workload | Control A | Candidate B |
| --- | --- | --- |
| nav 1e-30 / 1e-80 / 1e-150 | 52 / 102 / 172 (grid = viewport, below the grid budget) | 62 / 112 / 182 (grid = viewport) |
| restored-k80 | 4,019 / grid 60 | 3,999 / grid 60 |
| julia-reported-x | 9,257 / grid 39 | 9,237 / grid 39 |
| cycled-80x1e-30 | 4,019 / grid 60 | 62 / grid 62 |

Metrics per render:
- `reference_ms`: Mandelbrot primary reference orbit from `DeepZoomTimingStats`;
  for Julia, a separately timed `JuliaReferenceSampler.create` including grid
  construction.
- `first_region_ms` and `backend_ms`: first published region and full frame,
  including orbit, BLA and tiles.
- Camera arithmetic: the median of 25 repetitions of each of `PreciseRenderGrid.from`,
  wheel `zoomAt`, `shiftedByPixels`, the Copy text, Go to `destination`, and
  restore from decimal text.
- `pan_reuse_plan_ms`: `FrameReusePlanner.plan` for a 13x7 drag of the rendered
  frame.

The Mandelbrot backend keeps its default reference cache for one sequence and is
recreated for every sequence, so each measured first render builds its orbit.

Campaign: 10 process pairs in AB, BA, AB… order, each JVM with 1 cold, 3 warmup
and 5 measured sequences, giving 50 matched pairs per metric. Ratios are medians of
matched B/A samples. The 95% intervals come from the seeded hierarchical bootstrap
of `summarize_baseline_pairs.paired_statistics` (5,000 resamples, process pairs
first). The [policy](policies/viewport-precision-pr51.json) fixed workloads,
counts, limits and decision rules before the campaign. Single-sample exploratory
runs were used only to size it. All 20 JVMs exited successfully (wall time
109–131 s each); every cold, warmup and sample row is present, and frozen builds
were re-verified after the run.

## Results

### 1. Navigated deep views (control limit B/A ≤ 1.05)

| Workload | Metric | A ms | B ms | B/A | 95% interval | Gate |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| mandelbrot-nav-1e-30 | reference | 0.335 | 0.366 | 1.091 | 1.069–1.113 | fail |
| | first region | 2.52 | 2.64 | 1.055 | 1.023–1.080 | inconclusive |
| | full frame | 154.8 | 150.9 | 0.971 | 0.939–1.018 | pass |
| mandelbrot-nav-1e-80 | reference | 1.24 | 1.38 | 1.116 | 1.093–1.141 | fail |
| | first region | 4.08 | 4.34 | 1.068 | 1.044–1.082 | inconclusive |
| | full frame | 153.1 | 149.7 | 0.994 | 0.942–1.048 | pass |
| mandelbrot-nav-1e-150 | reference | 4.12 | 4.37 | 1.055 | 1.042–1.068 | inconclusive |
| | first region | 8.83 | 9.36 | 1.058 | 1.049–1.070 | inconclusive |
| | full frame | 336.3 | 352.2 | 1.059 | 1.025–1.108 | inconclusive |
| julia-nav-1e-30 | reference | 0.200 | 0.202 | 0.970 | 0.923–1.018 | pass |
| | first region | 1.61 | 1.63 | 1.006 | 0.981–1.037 | pass |
| | full frame | 676.6 | 677.2 | 0.988 | 0.953–1.014 | pass |
| julia-nav-1e-80 | reference | 0.598 | 0.622 | 1.036 | 1.023–1.081 | inconclusive |
| | first region | 2.83 | 2.86 | 1.013 | 1.000–1.044 | pass |
| | full frame | 2,222 | 2,238 | 1.007 | 0.963–1.020 | pass |
| mandelbrot-zoomout-1e-80 (3 steps) | reference | 1.17–1.18 | 1.23–1.25 | 1.056–1.069 | 1.032–1.089 | inconclusive |
| | first region | 3.49–3.55 | 3.57–3.61 | 1.011–1.039 | 0.991–1.054 | pass / inconclusive |
| | full frame | 144–151 | 139–143 | 0.959–0.986 | 0.920–1.036 | pass |

Decision: **inconclusive**. Retaining required every full-frame and first-region
upper bound ≤ 1.05, which three first-region rows and the Mandelbrot 1e-150 frame
miss. Rejecting required a full-frame lower bound > 1.05; none has one. The cost is
consistent and has a clear cause: ten more digits in the reference orbit and in
additional BigDecimal references (Mandelbrot 1e-150 builds about 10 extra orbits
per frame). In absolute terms it is ≤ 0.26 ms for the orbit, ≤ 0.53 ms for first
region and a median +16 ms on the slowest frame (336 ms). Control's grid precision
was **below** `PreciseRenderGrid`'s own pixel-depth budget on every navigated view.
That is the correctness defect PR #51 fixed, so the old precision is not an
acceptable baseline even if it were faster.

Zoom-out cache key: none of the three zoom-out steps reused a reference orbit on
either build. Each step still builds an orbit (≈ 1.2 ms), because the adaptive cap
changes with scale (13,658 → 13,608 → 13,558) and the cap is part of the cache
key. The "one cache key on zoom-out" effect therefore produced no cache hits in
this adaptive workload.

### 2a. Shared inflated input restored on both builds

All metrics stayed within the ≤ 1.05 control limit except one Go to. Examples
(A → B ms, B/A, interval):

| Workload | Full frame | Reference | Wheel zoom | Copy | Go to | Restore | Reuse plan |
| --- | --- | --- | --- | --- | --- | --- | --- |
| mandelbrot-restored-k80 | 173.3 → 166.3, 0.974 (0.937–0.994) | 0.316 → 0.307 | 0.247 → 0.255 | 0.625 → 0.628 | 0.719 → 0.704 | 1.051 → 1.045 | 1.157 → 1.163 |
| julia-restored-k80 | 662.7 → 643.2, 0.968 (0.940–0.997) | 0.266 → 0.257 | 0.260 → 0.264 | 0.621 → 0.625 | 0.716 → 0.707 | 1.048 → 1.042 | 1.180 → 1.163 |
| julia-reported-x | 5,702 → 5,649, 0.998 (0.981–1.016) | 1.71 → 1.70 | 1.17 → 1.16 | 1.68 → 1.66 | 2.63 → 2.90, 1.107 (1.087–1.125) | 3.23 → 3.22 | 2.35 → 2.41 (0.747–1.159) |

This confirms the hypothesis. `PreciseRenderGrid.from` already bounded render
arithmetic to the pixel-depth budget (grid 60 or 39 digits on both builds), so a
restored inflated view renders at the same cost. Camera arithmetic is also
unchanged, because the candidate keeps every retained input digit (only the
+20 guard on top is gone). A view that is already inflated stays as slow to
manipulate as before: about 0.1–1 ms per operation at 4,000 digits and
0.15–3.2 ms at 9,237 digits.

The identical-arithmetic restored rows show B 2–7% faster on some metrics (for
example Mandelbrot full frame 0.974, pan 0.932), which is not caused by the change.
Treat about ±5–7% as this host's practical resolution.

### 2b. The same workflow repeated 80 times on each build (target B/A ≤ 0.90)

| Metric | Mandelbrot A → B ms, B/A (interval) | Julia A → B ms, B/A (interval) |
| --- | --- | --- |
| Wheel zoom | 0.248 → 0.0024, 0.010 (0.009–0.010) | 0.260 → 0.0027, 0.010 (0.010–0.011) |
| Pan | 0.102 → 0.0007, 0.007 | 0.100 → 0.0008, 0.008 |
| Copy | 0.613 → 0.0015, 0.002 | 0.616 → 0.0014, 0.002 |
| Go to | 0.712 → 0.0014, 0.002 | 0.713 → 0.0016, 0.002 |
| Restore | 1.042 → 0.0012, 0.001 | 1.041 → 0.0010, 0.001 |
| Grid construction | 0.088 → 0.0009, 0.010 | 0.086 → 0.0010, 0.012 |
| Reuse plan for a drag | 1.174 → 1.060, 0.895 (0.863–0.920), inconclusive | 1.615 → 1.313, 0.875 (0.755–0.945), inconclusive |
| Full frame | 175.5 → 167.5, 0.950 (0.907–0.984) | 659.3 → 650.7, 0.987 (0.964–1.003) |
| First region | 1.97 → 1.88, 0.958 (0.927–0.971) | 1.32 → 1.31, 0.993 (0.960–1.016) |
| Reference | 0.308 → 0.310, 0.990 | 0.266 → 0.177, 0.674 (includes the control's 4,000-digit grid rebuild) |

Decision: **retain**. All six camera-arithmetic targets pass by two to three
orders of magnitude; the reuse-plan target is inconclusive. Render metrics pass
the control limit and stay within the host's resolution, which confirms that
render time barely changes. The benefit is that camera and UI arithmetic no longer
grows with session history: on control each cycle added about 47–54 digits (80
cycles → about 4,000), while the candidate stays at 62. Absolute savings at this
size are still ≤ about 1 ms per operation; costs grow with digit count, so longer
histories (the reported view had 9,237 digits) save proportionally more.

## Output checks

Pair-0 frame dumps compare control and candidate (escaped iteration count or
interior):

- 0 iteration mismatches in every Mandelbrot frame and in the Julia restored,
  cycled and reported frames. Bitwise-identical smooth values: Mandelbrot 1e-80,
  zoom-out steps, restored and cycled; Julia restored.
- `julia-nav-1e-30`: 6 pixels differ (4.0e-6). `julia-nav-1e-80`: 470 pixels differ
  (3.2e-4). Control renders those Julia views below the grid budget.

These are not proof of correctness on either side. Several deep renders are not
repeatable between samples **within one build**. Distinct sample fingerprints over
80 non-cold renders:

| Workload | Control A | Candidate B |
| --- | ---: | ---: |
| `julia-nav-1e-80` | 80 | 14 |
| `julia-nav-1e-30` | 3 | 1 |
| `julia-reported-x` | 80 | 80 |
| `mandelbrot-nav-1e-30` | 20 | 29 |
| `mandelbrot-nav-1e-150` | 33 | 40 |

Mandelbrot iteration counts still matched, so the variation is in smooth values
on multi-reference paths. Exact numerical correctness remains the job of the unit
and oracle tests from PR #51.

## Not measured

- Gesture-to-visible latency (`interaction-latency`, JavaFX) on an inflated view:
  not run. Section 2a bounds the camera, grid and reuse-plan arithmetic on a
  9,237-digit view at 0.15–3.2 ms per operation on both builds. That makes a
  frame-level difference between the builds unlikely for restored views, but
  JavaFX publication and physical presentation are unmeasured.
- Other window sizes (only 1512x982), AA, orbit traps, Burning Ship, Tricorn and
  Multibrot (no deep backend), GPU, Intel and other platforms.
- The fixed-iteration zoom-out cache behaviour, and memory or allocation effects.
