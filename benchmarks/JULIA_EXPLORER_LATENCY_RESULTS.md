# Explore Julia Parameters: latency and smoothness on a Retina Mac

Date: 2026-11-23 (UTC). Goal: measure the gesture-to-visible latency, frame
cadence and per-frame cost of **Render > Explore Julia Parameters…** as
delivered in PR #87, and decide whether direct GPU texture presentation is
worth prototyping. Protocol: [JULIA_EXPLORER_LATENCY.md](JULIA_EXPLORER_LATENCY.md).
Selected numbers: [summary CSV](JULIA_EXPLORER_LATENCY_SUMMARY.csv). A
[follow-up](#follow-up-bright-point-filter-on-the-gpu) moved the bright-point
filter to the GPU and was retained; the measurements before it describe the
code state of PR #87.

## Environment and code state

Apple M3 Pro, 12 logical processors, built-in Liquid Retina XDR display at the
default scaling (1728x1117 logical points, output scale 2.0, JavaFX pulses at
about 8 ms), macOS 27.2.1 (as reported by Java), ARM64 Java 26.0.1, JavaFX
26.0.2 (requested pipeline `mtl,es2,sw`), MoltenVK, 2 GiB heap, AC power. No
thermal or performance warning was recorded before or after the campaign.
`caffeinate -di` held the system and display assertions for the whole
controller script and no `caffeinate` process or assertion remained afterwards.
No other test or benchmark ran during the campaign.

Code: branch `chore/julia-explorer-latency` on base and merge base `ceb0dd7`
(PR #87 merged), with the uncommitted recording points and driver described in
the protocol. The map's behavior is unchanged from `main`. Raw CSVs, logs, the
candidate diff and power snapshots are in a private archive
(`benchmarks/julia-explorer-latency-private-20261122/`), not in the public
checkout.

Runs, in this order:

| Run | JVMs | Content |
| --- | ---: | --- |
| cold-open | 5 | First map opening in a fresh JVM, 1680x1000, 300 iterations |
| cold-drag | 5 | Drag started in the same FX event as the first opening |
| control | 1 | 1200x760 and 1680x1000 × 300 and 1000 iterations × click and drag; 2 warmup + 10 measured rounds each; no screen capture |
| pulse | 4 | JavaFX pulse logger, drag only, 1 warmup + 3 rounds per size and limit |
| screen | 1 | Robot screen capture, 1200x760, 300 iterations, 1 warmup + 3 rounds |

Every trial committed exactly the target constant. Every published frame of
the control run reached a post-layout pulse (0% superseded).

## First launch and kernel warm-up

| Measure (1680x1000, 300 iterations, 5 cold JVMs) | Median | Max |
| --- | ---: | ---: |
| Map opened → first frame submitted | 22.3 | 25.0 |
| First runtime call (kernel creation included) | 123.6 | 130.3 |
| of which outside the kernel (shader compilation, pipeline) | 98.3 | 104.6 |
| First bright-point filter (cold JIT; warm ≈ 3.5) | 12.0 | 14.1 |
| Map opened → first frame published | 167.4 | 173.2 |
| Map opened → first frame at a pulse | 168.0 | 176.3 |

A warm reopening of the map takes 49–58 ms to its first frame. The kernel is
not warm until the first frame has been published, about 170 ms after the map
opens. When the drag starts in the same event as the opening, the opening frame
is superseded but its native call cannot be interrupted: the first drag frame
waits for it. Drag input → first published frame is then 21.4 ms median but
108.8 ms p95 and 131.2 ms at most (240 inputs), against 20.5 / 29.9 / 40.9 ms
in warm drags. Whether warm-up finishes before the first gesture therefore
depends on the user: a gesture that starts more than about 170 ms after the
window appears meets a warm kernel; a faster one sees up to about 130 ms of
extra delay on its first frames.

## Input response, frame cadence and release

Milliseconds, control run, medians with nearest-rank p95 in parentheses.
"Response" is the first frame whose render started at or after the input; the
pulse column is the main scene's post-layout pulse that carries that frame.

| Size (physical) | Limit | Drag input → frame | → pulse | Drag frame interval | Click frame interval | Release → first frame, drag / click |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 2400x1520 | 300 | 14.3 (25.9) | 17.3 (32.4) | 14.7 (18.6) | 9.9 (18.3) | 13.6 / 32.0 |
| 2400x1520 | 1000 | 12.3 (25.5) | 15.1 (30.9) | 14.9 (18.6) | 9.8 (18.1) | 19.7 / 46.0 |
| 3360x2000 | 300 | 20.5 (29.9) | 25.4 (33.6) | 16.6 (19.2) | 16.6 (19.1) | 19.2 / 44.3 |
| 3360x2000 | 1000 | 16.2 (32.1) | 20.4 (35.5) | 17.0 (25.6) | 16.6 (25.4) | 27.0 / 45.8 |

480 drag inputs and 5,000–7,200 frames per row. The largest frame interval in
any row is 37 ms. A click's first frame comes later than a drag's because the
map's animation timer only records a time origin on its first tick after
release and submits on the second.

Arrival at the selected point is governed by the map's pacing, not by
rendering:

| Size | Limit | Release → final frame, drag | Release → final frame, click | Frames per click |
| --- | ---: | ---: | ---: | ---: |
| 2400x1520 | 300 | 6,719 (6,935) | 8,492 (8,682) | 712 |
| 2400x1520 | 1000 | 6,880 (7,212) | 8,557 (8,880) | 720 |
| 3360x2000 | 300 | 7,742 (7,953) | 9,526 (9,874) | 590 |
| 3360x2000 | 1000 | 8,282 (8,588) | 9,951 (10,253) | 610 |

The map commits 2–7 ms after the final frame. The 72-point path between the
two constants takes 7–10 s although frames keep arriving every 10–17 ms:
near changing edges the pacing cuts the per-frame step to its minimum budget,
so motion slows to a few map points per second. The time grows by about 1 s on
the larger canvas because each frame takes longer while the step budget is
time-based. This is the dominant visible delay of the feature and a product
question, not a rendering cost; this measurement does not change the pacing.

## Where a frame's time goes

Medians in milliseconds over all drag frames (1200x760: 5,796 frames at 300;
1680x1000: 5,340 at 300 and 5,155 at 1000).

| Stage | 2400x1520, 300 | 3360x2000, 300 | 3360x2000, 1000 |
| --- | ---: | ---: | ---: |
| Submit → worker start | 0.46 | 0.36 | 0.37 |
| GPU dispatch (kernel, submission, fence) | 5.08 | 6.54 | 6.96 |
| ARGB readback into `int[]` | 0.79 | 1.09 | 1.02 |
| Setup, upload, runtime overhead | < 0.05 | < 0.05 | < 0.05 |
| CPU bright-point filter | 2.62 | 3.56 | 3.80 |
| FX queue wait | 0.03 | 0.02 | 0.02 |
| Copy into `PixelBuffer` and `updateBuffer` | 1.01 | 0.84 | 0.69 |
| **Submit → published** | **10.02** | **12.51** | **13.07** |
| Published → post-layout pulse | 3.73 | 4.34 | 4.48 |
| Prism painting of the main window (pulse logger, 1 ms resolution, mean) | 0.09 | 1.97 | 1.91 |
| Prism presenting (mean) | 0.00 | 0.00 | 0.00 |

p95 values: dispatch 8.5 / 8.4 / 14.9 ms, filter 3.3 / 4.0 / 5.1 ms, readback
1.0 / 1.5 / 1.4 ms, copy 1.2 / 1.3 / 1.1 ms. Click frames show the same split.
At 1000 iterations the dispatch grows only slightly on this path, unlike the
19.23 ms spiral scene of the [GPU Julia benchmark](GPU_JULIA_RESULTS.md): the
two endpoints and the path between them contain fewer slow-escaping pixels.

At 3360x2000 the GPU kernel is about half of the 12.5 ms software frame. The
CPU image path after it, readback, bright-point filter and `PixelBuffer` copy,
is 5.5 ms; with Prism's texture upload and drawing it is about 7.5 ms. The
bright-point filter alone is the largest CPU stage. The frame does not fit one
8.3 ms pulse, so frames land on every second pulse (16.6 ms cadence); at
2400x1520 the 10 ms frame alternates between one and two pulses during clicks.

## Screen-capture proxy (perturbed)

The screen run is not a latency result. Robot capture of the 64x16-point strip
runs on the FX thread and took 14.0–15.8 s of each 17–20 s trial, so frame
intervals rose from 10–15 ms to 34 ms, release → final screen observation took
17.1–20.5 s and drag input → screen observation was 98.8 ms median. Publication → screen
observation was 48.6 ms median, which includes the capture period. The run does
show that frames reach the composited window in order: at most 0.37% of frames
went unobserved, and the screen identified the expected final frame in every
trial. No unperturbed compositor or panel timestamp was measured.

## Decisions

- **Gesture-to-visible latency on the measured Mac: measured, not a gate.**
  Warm drag input reaches a published frame in 12–21 ms median and 26–32 ms
  p95, and a post-layout pulse in 15–25 ms median and 31–36 ms p95. Software
  frames arrive every 10–17 ms, p95 at most 26 ms, with no superseded frame.
  No predeclared threshold existed, so this is evidence for the open roadmap
  item, not acceptance.
- **Kernel warm-up: measured.** The first frame after opening takes about
  170 ms, 100 ms of it kernel creation. A gesture within that window waits up to
  about 130 ms. Warming the kernel earlier (for example when Julia GPU becomes
  available) would hide it; not implemented here.
- **Direct GPU texture presentation: inconclusive, not prototyped.**
  Readback, copy and Prism painting together cost about 4 ms of a 12.5 ms frame
  at 3360x2000 (about 2 ms at 2400x1520). That is material, but it is neither
  the largest stage nor the visible bottleneck: the CPU bright-point filter
  costs about 3.5 ms by itself, and arrival at the target is limited by pacing
  for seconds. Direct presentation would also need a JavaFX external-texture
  path that has no public API. Measure a GPU-side bright-point filter first: it
  would remove the largest CPU stage while keeping the current image path, and
  bring the 3360x2000 frame close to one pulse. Reconsider direct presentation
  only if readback and publication then dominate the frame.
- **Pacing: reported for an owner decision.** Clicks and drags across
  dendrite-like regions take 7–10 s to settle at the selected point. Whether
  that is the intended visual continuity is a product question outside this
  measurement.

## Not covered

Physical input-device and OS input-delivery latency (synthetic JavaFX events),
unperturbed compositor and panel scanout timing, ProMotion refresh behavior
beyond the observed 8 ms pulses, external displays and other scales, other
Julia constants and paths, Windows/Linux and Intel/AMD Macs, the CPU fallback
(none exists for the map), sustained thermal behavior beyond this campaign, and
memory growth over long sessions.

## Follow-up: bright-point filter on the GPU

Candidate: run the bright-point filter as a second compute pass in the same
command buffer as the Julia preview kernel and read back only the filtered
frame, removing the CPU filter from the map's frame path. Control: the code
state measured above (CPU filter on the Julia GPU worker), frozen as a separate
build before the candidate was written.

Declared before the candidate was implemented or measured:

- **Correctness:** for every tested frame, the GPU-filtered colors equal the CPU
  `JuliaBrightPointFilter` applied to the unfiltered GPU colors of the same
  request, bit for bit, including the one-pixel border, odd sizes and frames
  with dimmed pixels. Any mismatch rejects the candidate.
- **Workload:** the control-run matrix (1200x760 and 1680x1000 × 300 and 1000
  iterations), drag only, 1 warmup and 6 measured drags per configuration.
  Three JVM pairs alternating control and candidate (ABBA BA order: control,
  candidate, candidate, control, control, candidate), all without screen
  capture, under one `caffeinate -di` controller.
- **Retain:** pooled median render start → published frame at least 15% shorter
  at 3360x2000 for both limits, and in every configuration the pooled median and
  p95 of drag input → first post-layout pulse no more than 5% worse than control.
- **Reject:** render start → published frame less than 5% shorter at 3360x2000
  for either limit, or any configuration with drag input → first post-layout
  pulse median or p95 more than 10% worse.
- Otherwise **inconclusive**. Arrival time after release is reported but not
  gated: it is set by the pacing.

### Results

Campaign 2026-11-23 02:40–03:05 UTC, same machine and conditions as above
(AC power, no thermal or performance warning before or after, `caffeinate -di`
around the controller, nothing else running). Control: the frozen build of the
code state measured above; candidate: the same code with the GPU pass. Both
builds compiled from the same base `ceb0dd7`. Six JVMs in the declared order,
each with 1 warmup and 6 measured drags per configuration; 18 measured drags
and 864 drag inputs per configuration and build.

**Correctness.** The native check (`GpuJuliaNativeTest`, `-Pgpu-smoke`) renders
five constants, two colorings, 300 and 1000 iterations at 193x129, 2x2, 2x3,
3x3 and 641x401, then 193x129 again, unfiltered and filtered alternately, and
requires the GPU-filtered frame to equal the CPU reference applied to the
unfiltered GPU frame, with at least one dimmed pixel overall. It passes; with
the retained contrast changed from 16 to 17 in the shader it fails at the
first dendrite frame.

Pooled medians in milliseconds, p95 in parentheses, control → candidate:

| Size | Limit | Render start → published | Drag input → pulse | Frame interval at pulse |
| --- | ---: | ---: | ---: | ---: |
| 2400x1520 | 300 | 9.01 (13.95) → 7.03 (11.31), −21.9% | 15.95 (31.48) → 14.82 (28.40), −7.1% | 14.65 → 8.42 |
| 2400x1520 | 1000 | 8.30 (14.53) → 7.17 (12.81), −13.7% | 13.91 (29.53) → 13.94 (27.15), +0.2% | 10.31 → 8.51 |
| 3360x2000 | 300 | 12.35 (14.73) → 6.57 (9.67), −46.8% | 24.22 (34.33) → 13.07 (26.95), −46.0% | 16.65 → 8.34 |
| 3360x2000 | 1000 | 11.90 (21.35) → 6.29 (18.58), −47.1% | 20.31 (37.71) → 13.25 (26.41), −34.7% | 16.65 → 8.39 |

The CPU filter's 2.3–3.6 ms disappears from every frame. The candidate's
dispatch includes the second pass and still takes 4.3–4.7 ms median, against
4.0–6.4 ms in the control; the GPU pass is therefore cheap, but this
host-timed split cannot separate it from clock or contention effects. At
3360x2000 the frame now fits one 8.3 ms pulse, so the frame interval halves.
Because the pacing step budget is time-based, release → final frame also
shortened, by 8–32% (for example 7,951 → 5,433 ms at 3360x2000 and 300
iterations), with 5–23% more frames per drag.

**Exception: the first frame after opening the map is slower.** The first open
in each JVM (1200x760, 300 iterations, n = 3) took 161 → 257 ms median to its
first frame (runtime call 121 → 168 ms; the candidate compiles a second shader
and allocates the filtered buffer). Reopening the map, n = 3 per
configuration, took 47.5–58.0 → 56.1–56.4 ms: the filtered buffer is freed while
the map is closed and its first readback after reallocation takes 2.5–5.4 ms
instead of 0.4–0.9 ms. The gates did not cover opening; this cost is recorded,
not waived silently.

**Memory budget (found in review).** The filtered output is a second ARGB
buffer in the 128 MiB `fractal.gpu.juliaPreview.maxBytes` budget, which would
have halved the largest map frame to about 16.8 megapixels (below a full-screen
6K window or a 5K display at More Space). The retained version allocates it
only when it fits and otherwise filters that frame on the CPU as before; a
native test covers a frame that fits only unfiltered. No measured frame here
(at most 3360x2000, about 54 MB for both buffers) reaches that limit, so the
numbers above are unaffected; frames above it keep the control's CPU cost.

**Decision: retain.** Both 3360x2000 limits improve by at least 15% (46.8% and
47.1%), and no configuration's drag input → pulse median or p95 is more than 5%
worse (largest change +0.2% median, 2400x1520 at 1000 iterations). The CPU
filter is no longer in the frame path; `JuliaBrightPointFilter` remains as the
CPU reference for the native check and the hardware-independent test runtime.

**Direct GPU presentation after this change.** At 3360x2000 the frame is now
about 6.5 ms from submission to publication, of which readback (0.9 ms) and the
`PixelBuffer` copy (0.7–0.8 ms) are about 1.7 ms, plus about 2 ms of Prism
painting after the pulse. It already fits one 120 Hz pulse; direct presentation
could shorten input → pulse by at most a few milliseconds and stays
inconclusive and not prototyped.
