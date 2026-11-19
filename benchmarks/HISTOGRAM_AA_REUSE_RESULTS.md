# Histogram AA sample reuse across pans

Decision (2026-11-18): **retain**, with the limits below. This is a two-pair
ABBA comparison with four JVM starts, not a timing gate v2 campaign. The measured
effect is far beyond any gate threshold used in this repository, but neither the
memory gate nor the timing gate was run.

## Problem

With Histogram Coloring, the completed frame of a pan maps its samples through its
own histogram, so the AA pixels shifted from the previous frame are antialiased
again (roadmap item "Histogram-consistent colors during interaction"). Without
reuse, that pass sampled every candidate of the frame: on a 2400x1520 seahorse
frame, about 411,000 candidates at 16 samples each. An exploratory profile showed
sampling at about 75% of the AA workers' time and the distance-estimate candidate
test at about 25%.

## Candidate

`HistogramSampleStore` (in `export`) keeps the coloring-independent work of
standard-zoom histogram passes for the bound frame: the subpixel samples of each
sampled candidate (smooth iterations and escape flags, the only values
`HistogramPaletteColoring` reads) and each tested pixel's distance-estimate
result. Frame reuse records the pixel shift in constant time on the FX thread, and
the next pass moves the values on the AA coordinator without the service lock. A
reused frame keeps the origin of its source grid, so the same pixel has
bit-identical subpixel coordinates there. The pass therefore publishes exactly
what a pass sampling everything again would publish. Shifts that move the grid
origin, change the iteration limit or bind another frame clear the store, as do
deep-zoom frames, whose samples depend on the reference orbit. The store has a
byte budget (`fractal.aaHistogramCache.maxBytes`, default 256 MiB): samples that
do not fit are calculated again.

## Method

- Control: the branch candidate without the store, that is, with
  `InteractiveAntialiasService` and `AdaptivePngExportService` at the base
  revision. Candidate: the branch with the store. Both were compiled from
  uncommitted copies of the same working tree (base revision `f778166`), which
  differ only in these files.
- Driver: `BaselineInputBenchmark` with `baseline.fixtures=mandelbrot-histogram-aa`,
  `baseline.sizes=2400x1520`, `baseline.fx.modes=FAST,REFINED`,
  `baseline.input.gestures=drag,trackpad`, 2 warmups and 10 runs per case.
  The order was control, candidate, candidate, control, in separate JVMs.
- Correctness: every trial passed the driver's bit-exact ARGB control, 52
  verifications per run. Control and candidate share the same control, a fresh
  AA pass of the target frame.
- Environment: macOS 27.2.1, Apple Silicon (aarch64, 12 processors), JDK 26.0.1,
  JavaFX 26.0.2. Active graphical session, under `caffeinate -di`.

Three candidate builds were measured against the same control, each with its own
ABBA series. The first moved the values on the FX thread inside frame reuse and
delayed the first FAST base publication by 6 to 9 ms (drag 23.4 to 31.3–33.3 ms).
The second deferred the move to the AA pass, which removed that delay. The third,
the final implementation, also charges the budget for every per-pixel array and
whole tiles, and marks charged tiles with a per-move stamp instead of a hash set,
whose lookups had added several milliseconds to the move. Only the third build is
reported below.

## Results

Medians in milliseconds from the last input, per run (control 1 / candidate 1 /
candidate 2 / control 2):

| Gesture, mode | Completion | Base publish | First AA publish |
| --- | --- | --- | --- |
| drag, FAST | 631 / 159 / 156 / 617 | 25.6 / 22.8 / 23.8 / 23.5 | 79.5 / 81.0 / 82.1 / 75.6 |
| drag, REFINED | 573 / 133 / 128 / 610 | — | 50.8 / 54.2 / 52.8 / 49.9 |
| trackpad, FAST | 630 / 144 / 144 / 628 | 11.2 / 8.6 / 9.1 / 10.2 | 65.9 / 64.6 / 68.3 / 66.9 |
| trackpad, REFINED | 599 / 133 / 131 / 616 | — | 53.6 / 52.7 / 52.6 / 53.3 |

Completion after a pan drops by about 75% in every case. Base publication is
unchanged. The first AA tile arrives up to about 6 ms later in drag, which is
the time spent moving the values before the pass starts; trackpad shows no
consistent difference.

## Comparison with `main`

The control above already recalculates shifted histogram AA, so the 75% is
relative to that implementation, not to `main`. `main` keeps shifted AA pixels in
the previous frame's mapping and shows a seam at the pan border. An independent
verification (2026-11-18, same machine class, 2400x1520, screen-observed, 10
repeats per build) measured the time from the last pan event to the completed
frame as 151 → 252 ms in FAST, 161 → 252 ms in REFINED and 149 → 150 ms in
Base only (`main` → this branch). The remaining cost is the full-frame histogram
AA pass that the completed frame needs for consistent colors.

## Limits

- Two pairs on one Apple Silicon machine. Timing gate v2 and memory gate v2 were
  not run, and no other platform was measured.
- Memory was not measured. The budget charges 13 bytes per pixel for three
  frame-sized arrays (a block reference counted as uncompressed, an entry index
  and a distance flag), about 47 MB at 2400x1520. Each committed tile is charged
  whole, with about 8 bytes per sample of each sampled candidate (about 53 MB for
  the measured frame), while any of its entries remains in the frame. With the
  default 256 MiB budget, frames whose per-pixel arrays alone exceed it (above
  about 20 megapixels) keep no values and antialias as before; a pan or resize to
  such a frame clears the store before allocating anything for it.
- Deep-zoom histogram passes and Orbit Trap passes are unchanged.
- Raw outputs stay private. This report is the selected summary.
