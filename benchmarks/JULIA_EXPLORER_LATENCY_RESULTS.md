# Explore Julia Parameters: latency and smoothness on a Retina Mac

Date: 2026-11-23 (UTC). Goal: measure the gesture-to-visible latency, frame
cadence and per-frame cost of **Render > Explore Julia Parameters…** as
delivered in PR #87, and decide whether direct GPU texture presentation is
worth prototyping. Protocol: [JULIA_EXPLORER_LATENCY.md](JULIA_EXPLORER_LATENCY.md).
Selected numbers: [summary CSV](JULIA_EXPLORER_LATENCY_SUMMARY.csv). A
[follow-up](#follow-up-bright-point-filter-on-the-gpu) moved the bright-point
filter to the GPU and was retained; the measurements before it describe the
code state of PR #87. A [second follow-up](#follow-up-map-pacing) made
the map point's speed independent of the distance and slower near the set's boundary. A
[later follow-up](#follow-up-kernel-warm-up-after-the-julia-view-renders) warms the
kernel once the Julia view has rendered and was retained.

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
  measurement. The owner later chose a speed independent of the distance; see
  [map pacing](#follow-up-map-pacing).

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

## Follow-up: map pacing

Date: 2026-11-23 (UTC). Candidate: branch `feature/julia-explorer-pacing` on
base and merge base `2a33d4f`. Control: `main` at `2a33d4f` (the code state of
the GPU-filter follow-up), built in a separate worktree. Environment as above
(Apple M3 Pro, macOS 27.2.1, Java 26.0.1, JavaFX 26.0.2, AC power). Each run is
one JVM with the full default matrix (2 warmup and 10 measured rounds of click
and drag per size and limit, no screen capture) under `caffeinate -di`, with
nothing else running; every trial committed exactly its target constant. Raw
CSVs and logs are in a private archive
(`benchmarks/julia-explorer-pacing-private-20261122/`). No paired-process gate
was declared: the change is a decided product behavior, not an optimization.

**Rejected first candidate: ease-out to a deadline.** The owner first asked for
a click to arrive within about 0.6 s and a drag to settle within 0.5 s of
release. That candidate eased out (cubic, bent toward quadratic near changing
edges) to 0.55 s after a click and 0.45 s after each drag event. Measured
against the control in one controller script (control first): release → final
frame 6.9–8.0 s → 563–570 ms median for clicks and 5.4–6.5 s → 202–208 ms for
drags. On inspection the owner found that long moves changed frames too fast,
because the speed grew with the distance, and asked for one constant speed
instead, with no slowdown near edges.

**Rejected second candidate: constant 130 points per second.** One cruise speed
with no edge sample, reached in 50 ms (2,600 points/s²) and braked at the same
rate, measured in its own JVM against the same control run: release → final
frame 593–600 ms median for the 72-point click. On inspection the owner found it
visually too fast.

**Rejected third candidate: 100 points per second** (2,000 points/s²), measured
the same way: release → final frame 758–768 ms median for the 72-point click;
the workload's drag (about 94 points per second) settled before release. The
owner found it still too fast.

**Rejected fourth candidate: 70 points per second** (1,400 points/s²), measured
the same way: release → final frame 1,068–1,078 ms median for the 72-point
click and 77–81 ms for the drag. The owner found it still too fast.

**Rejected fifth candidate: 50 points per second** (1,000 points/s²), measured
the same way: release → final frame 1,483–1,495 ms median for the 72-point
click and 490–498 ms for the drag. The owner found it still too fast, especially
where detailed Julia sets change quickly, and asked to bring back a speed that
depends on the area.

**Rejected sixth candidate: 40 points per second, slowing to 10 on changing frames.**
`JuliaFramePacing` keeps the speed independent of the distance but restores the
64x40 edge sample: where consecutive published frames change on more than 2.5% of
the sampled edges, the cruise speed drops toward 10 logical map points per second
(within about 0.1 s) and recovers toward 40 points per second in quiet areas
(over about 0.4 s). The point reaches cruise speed in 50 ms (800 points/s²) and
brakes at the same rate to stop exactly at the target; a frame longer than 50 ms
advances only as far as a 50 ms frame. Unlike the original pacing, the speed
cannot fall below 10 points per second and does not grow with the distance.
Measured in its own JVM against the same control run:

| Size | Limit | Release → final frame, click | Release → final frame, drag | Frames after release, click / drag |
| --- | ---: | ---: | ---: | ---: |
| 2400x1520 | 300 | 7,645 → 2,783 (p95 2,823) | 5,829 → 1,624 (p95 1,650) | 764 → 239 / 536 → 146 |
| 2400x1520 | 1000 | 8,005 → 2,739 (p95 2,811) | 6,497 → 1,650 (p95 1,695) | 753 → 252 / 523 → 163 |
| 3360x2000 | 300 | 6,908 → 2,677 (p95 2,706) | 5,422 → 1,567 (p95 1,594) | 730 → 240 / 546 → 134 |
| 3360x2000 | 1000 | 8,031 → 2,699 (p95 2,778) | 6,340 → 1,619 (p95 1,656) | 711 → 221 / 478 → 147 |

Medians in ms, control → candidate. At 40 points per second the 72-point click
would take about 1.85 s; edge adaptation adds about 0.9 s on this path between a
dendrite and the rabbit (at most 7.2 s if every frame of the path changed
heavily). The workload's drag moves about 94 points per second, faster than the
cruise speed, so the point trails the pointer and finishes about 1.6 s after
release. Frame cadence is unchanged (publication interval median 8.8–9.7 ms
against 8.4–10.6 ms).

Drag input → first published frame changed from 11.2 / 11.6 to 16.0 / 14.0 ms
median at 2400x1520 (1000 / 300 iterations) and from 9.3 / 10.6 to 21.2 /
12.6 ms at 3360x2000; p95 at 3360x2000 from 23.0 / 20.9 to 39.2 / 23.2 ms. The
GPU call during drags is up to 1.1 ms longer in the median at 3360x2000. All
rejected candidates showed the same direction with smaller medians at 1000
iterations. The edge sample costs the same per frame as in the control. The
likely cause is the workload: the control's point trails far behind the pointer
and renders constants near the start of the path, while the candidates render
constants along the whole path, where frames at 1000 iterations cost more. The
recording does not log each frame's constant, so this attribution is not proven;
no input-latency threshold exists, and the change is recorded, not waived.

On inspection the owner found it still too fast at the Mandelbrot set's
boundary.

**Retained candidate: the sixth candidate plus a boundary slowdown.** Close to
the set's boundary the cruise speed also falls in proportion to the distance from
it, from 40 points per second at 8 map points to 3 points per second on the
boundary, whichever of the two limits is lower. The slowdown is predictive: it
does not wait for frames to change. Outside the set the distance is the exterior
distance estimate (1,000 iterations, one orbit per frame on the FX thread);
inside it is the distance to the nearest escaping sample of a 280x192,
1,000-iteration render of the map grid, built once with the thumbnail (about
3 ms to render and 10 ms to build on this Mac). Interior distances are accurate to
about a map point; a dent thinner than that, such as the main cardioid's cusp, is
not resolved. Measured in its own JVM (`boundary3-1`), compared with the sixth
candidate's run:

| Size | Limit | Release → final frame, click | Release → final frame, drag | Frames after release, click / drag |
| --- | ---: | ---: | ---: | ---: |
| 2400x1520 | 300 | 2,783 → 9,710 (p95 9,721) | 1,624 → 8,684 (p95 16,822) | 239 → 881 / 146 → 789 |
| 2400x1520 | 1000 | 2,739 → 9,707 (p95 10,727) | 1,650 → 8,684 (p95 9,765) | 252 → 823 / 163 → 727 |
| 3360x2000 | 300 | 2,677 → 9,704 (p95 18,769) | 1,567 → 8,689 (p95 10,587) | 240 → 972 / 134 → 913 |
| 3360x2000 | 1000 | 2,699 → 9,707 (p95 10,841) | 1,619 → 8,692 (p95 8,701) | 221 → 793 / 147 → 698 |

Medians in ms. The measured path from Seahorse Valley to the period-3 bulb runs
along the main cardioid's boundary: the point is within one map point of it for
most of the way, so the 72-point click takes about 9.7 s, the same as a
simulation of the pacing over the boundary distance alone (9.69 s). The
changing-frame limit did not add measurable time on this path. A path through
quiet areas still moves at 40 points per second. The drag's point trails the
pointer and finishes about 8.7 s after release.

Nine of the 80 measured trials took more than 0.3 s longer than the median and
set the p95 values above. In four of them (one click and three drags, all at
300 iterations) published frames arrived every 70–150 ms for part of the motion
while each GPU call still took 7–12 ms: the click took 18.9 s, the drags
9.7–16.9 s. In the other five (two clicks, three drags) a single gap of
1.1–1.2 s between published frames added about 1 s (9.8–10.8 s). The pacing
advances at most 50 ms per frame, so such gaps stretch the motion. These data do
not show where the delays arose; their cause remains open, and a repeat run is
needed to tell whether they recur.
Drag input → first published frame: 13.3 / 15.8 ms median
at 2400x1520 (300 / 1000 iterations) and 12.6 / 16.3 ms at 3360x2000.

**Decision: retain** the boundary-aware pacing as the owner's requested
behavior. Not covered: physical pointer input, other hardware, frame rates other
than the display's 120 Hz (the changed-sample count, and so the frame-based
slowdown, grows with the step per frame), moves longer than the measured
72 points, and the cause of the slow-frame episodes.

## Experiment: 5% of the remaining Julia-parameter distance per frame

On 2026-11-26 UTC, the control was local `main` at `2a33d4f` and the candidate
was the uncommitted `feature/julia-explorer-five-percent` diff on that base.
Both used the same Ice palette, GPU bright-point filter, 256 iterations and
900x600 logical (1800x1200 physical) canvas. The Julia GPU diagnostic ran on
macOS arm64 with Java 26.0.1, JavaFX 26.0.2 and the requested `mtl,es2,sw`
pipeline. Each variant and path ran in its own JVM, with one warmup and four
measured clicks in alternating directions. Inputs were synthetic JavaFX
events, and screen capture was disabled. Both algorithms committed the exact
target in every trial. `caffeinate -di` held both assertions throughout the
campaign. The assertions were released on exit.

| Path | Release to final publication, median | Published frames per click, median | Post-layout frame interval, p95 |
| --- | ---: | ---: | ---: |
| Short, -0.38 + 0.61i to -0.44 + 0.61i | 2429 to 1060 ms | 282 to 122 | 9.55 to 9.50 ms |
| Standard, dendrite to rabbit (72 map points) | 6738 to 1586 ms | 733 to 175 | 16.45 to 16.01 ms |

The candidate advances 5% on each submitted animation frame. It renders the
exact target once the remaining distance is within 0.01 map points (using the
gesture's scale). The shorter time comes from using fewer intermediate frames,
with a larger initial step on longer paths. The experiment is inconclusive for
adopting this behavior as a product default. It sampled two click paths on one
machine. Drag timing, other displays, and physical presentation were outside
this campaign.

### Follow-up: separate drag and click motion

The same branch then limited the 5% step to drag, including the motion after
release. Clicks use the boundary distance and changes in the published Julia
image to limit their speed. A fresh diagnostic on the standard 72-point path
used the same canvas, iteration count, palette and GPU filter as above.
The driver alternated gesture order between rounds so that both click and drag
were measured twice in each direction, after one warmup round. All eight
measured gestures committed the exact target.

| Gesture | Release to final publication, median | Post-layout frame interval, p95 |
| --- | ---: | ---: |
| Click, adaptive speed | 9700 ms | 16.78 ms |
| Drag, 5% per frame | 1050 ms | 16.58 ms |

These durations describe the scripted path and gesture timing. The drag events
already cover part of the distance before release. No physical pointer or
display timing was measured. The session stayed active under `caffeinate -di`
and the owned assertions were released afterwards.

### Follow-up: gentler click start and finish

After updating the branch to `53ff62b`, an A/B run compared adaptive clicks
with the previous 50 ms acceleration and with the 140 ms eased start and
distance-based braking. Both variants used the short path above, the Ice
palette and 256 iterations on a 900x600 logical canvas. Each variant ran in its
own JVM with one warmup and four measured clicks. All eight clicks committed
the exact target. The elapsed time from release to the final publication was
1594 ms before and 1723 ms after (medians). The post-layout frame interval was
9.40 ms p95 in both. The driver used synthetic input on macOS arm64 with
`caffeinate -di`. The owned sleep assertions were released on exit.

## Follow-up: kernel warm-up after the Julia view renders

Candidate: when a CPU render of a Julia view that the GPU kernel supports
completes, the Julia GPU worker renders one 16x16 filtered frame of that view
and discards it. This compiles both shaders and creates their pipelines before
the map opens; see [GPU runtime](../docs/platform/GPU_RUNTIME.md#julia-gpu-paths).
Control: a frozen build of `main` at `34db3c9`. The candidate is the
uncommitted `feature/julia-kernel-warm-up` diff on the same base.

Declared before the candidate was written:

- **Workload:** `fractal.juliaLatency.sizes=1680x1000` (3360x2000 physical),
  300 iterations, `samples=0`, `warmup=0`, no screen capture, one JVM per trial.
  `cold=open` ran 5 JVM pairs in the order control, candidate, candidate,
  control, control, candidate, candidate, control, control, candidate.
  `cold=drag` ran 3 pairs in the order control, candidate, candidate, control,
  control, candidate. The driver lets the main view settle before it opens the map.
- **Correctness:** every trial commits exactly the target constant, and
  `GpuJuliaNativeTest` (`-Pgpu-smoke`) passes. It includes a warm-up followed
  by frames that must be bit-identical to those of a runtime without one.
- **Retain:** median map opened → first published frame (`cold=open`) at least
  50% shorter, and pooled p95 drag input → first published frame (`cold=drag`)
  no more than 5% worse.
- **Reject:** the median less than 20% shorter, or that p95 more than 10% worse.
  Otherwise **inconclusive**.

### Results

The campaign ran on 2026-11-27 from 00:37 to 00:41 UTC on the machine described
above, with Java 26.0.1, JavaFX 26.0.2 and LWJGL 3.4.2. Power was AC, with no
thermal or performance warning before or after, under one `caffeinate -di`
controller with nothing else running. After the campaign no `caffeinate`
process or assertion remained. All 16 JVMs committed the exact target constant.
The native check passed on the same machine.

Medians, with p95 and the maximum in parentheses, in milliseconds, control →
candidate:

| Measure | Control | Candidate |
| --- | ---: | ---: |
| Map opened → first frame published (`cold=open`, 5 JVMs each) | 208.1 (241.7) | 52.3 (65.2) |
| First frame's runtime call | 179.4 (210.4) | 22.6 (32.3) |
| of which outside the kernel (shader compilation, pipelines) | 148.4 (165.7) | 1.7 (4.7) |
| Map opened → first frame published (`cold=drag`, 3 JVMs each) | 203.1 (204.2) | 64.8 (68.2) |
| Drag input → first published frame (`cold=drag`, 144 inputs each) | 20.2 (132.2, 159.0) | 16.6 (25.3, 27.7) |
| Drag input → first post-layout pulse | 26.4 (134.2, 160.9) | 22.5 (32.4, 34.0) |

The first frame after opening the map is 74.9% shorter. It still includes about
22 ms from opening to the first submitted frame and the first full-size
allocation and readback. A drag started in the same event as the opening no
longer waits for kernel creation: its p95 fell by 80.8%. That puts it within
the warm-drag range measured above.

**Decision: retain.** Both retain conditions hold. The warm-up runs only after
the view's render has completed, so the measured renders do not include it. A map
opened while the first Julia render or the warm-up is still running waits for
the kernel; this campaign does not measure that case.

After this campaign, independent review led to two changes. The warm-up
pixels now come from the center of the view's own grid, which the precision
gate accepts whenever it accepts the frame. A 16x16 grid of the same viewport
could leave the FP32 range in some portrait views. The worker also no longer
skips the warm-up for a frame that is waiting, because that frame can still be
cancelled. Neither change affects the measured path. The landscape initial view
passed the gate either way. The settled main view queued the warm-up long
before the map opened, with no frame waiting, and it still renders one 16x16
frame. The measurements were not repeated. Not covered: other sizes and
iteration limits, warm reopening (unchanged), physical input, other hardware.
