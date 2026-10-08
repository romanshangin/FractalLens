# Interaction latency results

> Historical evidence: results and “current source” below refer to the dated
> experiment, not a new validation of this checkout. Only the
> [selected evidence](README.md) is included; entries marked “private archive”
> are unavailable in this repository. Original SHA values and recorded commands
> describe the original run and may require private revisions or inputs. See
> [provenance and replay limits](../docs/development/PUBLICATION.md).

Measured 2026-09-06 on Apple M3 Pro, macOS 26.6.2, Java 26.0.2, JavaFX 26.0.2+3,
2 GiB maximum heap. Viewport: 1200x760 logical / 2400x1520 physical pixels.
The graphics smoke log confirmed the ES2 pipeline on Apple M3 Pro, with vsync enabled.
GPU fractal calculation was disabled. No timing run overlapped the test suite or another benchmark.

## Finding: cached navigation spends about 1.87 seconds planning no work

Repeated home-to-zoom navigation reuses a completed frame. In all 60 measured zoom
trials the base service delivered one callback, with no intermediate base progress.
The median interval from render submission to that callback being queued was
1861–1886 ms across the wheel, wheel-burst and pinch cases. Controller preparation
and JavaFX callback queueing are outside that interval.

`ParallelFractalCalculator.createTileTasks` calls `ValidityMask.missingRowSpans`
for each 32x32 tile, even on a completely ready frame. For every tile row,
`BitSet.nextClearBit(rowFrom)` scans beyond that row/region. On a full mask it
searches to the end of the entire image over and over. The number of rows queried
and the length of the remaining bitset both grow with image size.

An isolated benchmark reproduced the delay using only the production validity mask
and tile partition. No JavaFX, fractal formula or AA was involved:

| Buffer | Production tile scan, median | Diagnostic complete-frame guard, median |
| --- | ---: | ---: |
| 1200x760 | 115.79 ms | 0.0092 ms |
| 2400x1520 | 1821.57 ms | 0.0328 ms |

Four times as many pixels cost 15.7 times as much scanning. Both diagnostic paths
return zero missing spans. The guarded path is an isolated experiment; it is **not**
installed in the production renderer, and this ratio is **not** an application speedup.
The agreement with the application trace identifies a concrete cached-frame planning
bottleneck independent of JavaFX. Partial frames can have different scan costs.

[Raw mask benchmark](INTERACTION_LATENCY_VALIDITY_SCAN.csv). Reproduction:
[ValidityScanBenchmark](../src/test/java/com/shangin/fractal/render/ValidityScanBenchmark.java).
Affected code: [ValidityMask](../src/main/java/com/shangin/fractal/render/ValidityMask.java)
and [ParallelFractalCalculator](../src/main/java/com/shangin/fractal/render/ParallelFractalCalculator.java).

## Production gestures without screen capture

Times below are medians in milliseconds from the **last input event**. They stop
at software publication/completion, not monitor presentation. Base publication can
include exact cached pixels. Refined normally hides the base image until AA tiles arrive.

| Mode | Gesture | Render starts | Base published | First AA published | Complete |
| --- | --- | ---: | ---: | ---: | ---: |
| FAST | wheel | 33.3 | 75.6 | 1940.2 | 2069.6 |
| FAST | wheel-burst | 33.7 | 79.7 | 1952.1 | 2131.8 |
| FAST | pinch | 20.1 | 64.7 | 1956.2 | 2085.8 |
| FAST | trackpad | 77.4 | 118.1 | 142.2 | 150.4 |
| FAST | drag | 22.6 | 63.6 | 81.5 | 84.3 |
| REFINED | wheel | 35.9 | — | 1948.2 | 2080.7 |
| REFINED | wheel-burst | 33.3 | — | 1948.1 | 2127.9 |
| REFINED | pinch | 20.3 | — | 1919.0 | 2046.3 |
| REFINED | trackpad | 81.8 | — | 188.9 | 196.5 |
| REFINED | drag | 23.2 | — | 101.9 | 103.4 |

Wheel, wheel-burst and pinch: 10 measured trials per mode/case after two warmups.
Pan and drag: five measured trials after one warmup, starting two zoom steps in
so panning is permitted. All are warmed navigation with production caches retained.

The preview transform itself is applied in less than 1 ms median in these controls.
Fast can expose cached base pixels well before the expensive no-work scan finishes;
Refined waits for AA, making the same background delay much more visible.
For trackpad pan, the initial wait is 77–82 ms and synchronous preparation is
41–68 ms; the first base callback is queued only 4–5 ms after submission.
Drag begins rendering at release; the roughly 23 ms last-drag-to-start interval
includes the scripted pause before release, not a separate 23 ms drag debounce.

The normal delays are 30 ms for wheel and 75 ms for live pinch/trackpad. Pinch finish
flushes pending rendering; scroll finish currently does not flush pan. These are
application policies, not an intrinsic minimum imposed by JavaFX.

AA can queue thousands of tile callbacks. In the cached zoom controls the maximum
AA callback queue wait had medians around 29–36 ms for the single wheel case, but
this is secondary to the 1.87-second planning interval. Queue-wait sums overlap
and must not be added as sequential wall-clock costs.

Zoom raw (private archive: `INTERACTION_LATENCY_ZOOM_CONTROL.csv`), [zoom summary](INTERACTION_LATENCY_ZOOM_CONTROL_SUMMARY.csv),
pan raw (private archive: `INTERACTION_LATENCY_PAN_CONTROL.csv`), [pan summary](INTERACTION_LATENCY_PAN_CONTROL_SUMMARY.csv).

## Screen-marker observations

Five measured trials per mode/case, one warmup. First preview is measured from the
**first input**; target details/completion are from the **last input**, tied to the
final render. Each observation decodes a unique marker in the same JavaFX scene.

| Mode | Gesture | First preview observed | Target details observed | Complete observed |
| --- | --- | ---: | ---: | ---: |
| FAST | wheel | 33.5 | 92.2 | 2126.1 |
| FAST | trackpad | 32.8 | 164.2 | 188.8 |
| FAST | drag | 32.3 | 85.6 | 122.1 |
| REFINED | wheel | 41.0 | 2075.5 | 2168.7 |
| REFINED | trackpad | 41.7 | 233.7 | 233.7 |
| REFINED | drag | 34.7 | 145.3 | 145.3 |

All listed screen observations were present in all five trials. These are
**capture-based scene-presentation proxies**, not physical scanout or direct inspection
of every fractal pixel. Synthetic JavaFX dispatch excludes hardware and OS input latency.

The observer is intrusive: Robot capture runs on the FX thread. Summed capture work
was about 1.72–1.88 seconds during the wheel trials, and successful probe intervals
had median per-trial maxima of 34–55 ms across cases. Some Refined wheel trials
had gaps up to 109 ms. Thus the observed 32–42 ms median initial feedback and later
publication-to-screen intervals include sampling/capture overhead; they cannot be
reported as pure JavaFX rendering or compositor latency. Screen-mode gesture spacing
also changes when capture occupies the event thread.

For example, complete-wheel publication was 2069.6 ms without capture versus 2078.5 ms
with capture in Fast, and 2080.7 versus 2132.5 ms in Refined. Pan/drag did not move
uniformly in the same direction, so no fixed capture correction is justified.
A less intrusive OS presentation trace or external high-speed recording is needed
for a precise input-to-photon measurement.

Screen raw (private archive: `INTERACTION_LATENCY_SCREEN.csv`), [screen summary](INTERACTION_LATENCY_SCREEN_SUMMARY.csv).

## Scope and validation

- The production rendering algorithm and interaction delays were not changed.
- The recorder is disabled in normal application runs. Enabled callbacks preserve
  recording/input/render identities; late work and observations cannot contaminate
  a new recording. Callback costs are aggregated rather than logging every tile.
- Portable suite: 359 tests, zero failures/errors, 20 opt-in tests skipped. The visible
  benchmark runs additionally exercise actual view handlers, rendering and screen decoding.
- The first home-view pan/drag trial attempts were no-ops because of camera bounds.
  They were excluded as an entire invalid fixture; the retained zoom control contains
  every measured zoom trial from that run. Pan/drag were rerun at a navigable scale.
- Initial screen attempts with failed marker reset/detection, and diagnostic smoke runs,
  were excluded. Retained screen data come from one complete successful final run.
- This is a current-state diagnosis, not a paired before/after AA optimization study.
  Sample counts are modest, modes run in fixed order, ordinary desktop activity remains,
  and thermal behavior/long-duration memory were not controlled. Saved p95 values are
  nearest-rank statistics; with 5 or 10 observations they equal the observed maximum.

## Next change justified by these results

First fix cached-frame planning: skip work for a fully ready frame and bound missing-bit
searches to the requested row/region, including nearly full masks. Protect exact retained
samples, cancellation and partial-region behavior, then rerun this interaction benchmark
on the same fixtures. Next evaluate trackpad settle/finish policy and synchronous reuse/
surface preparation. Callback batching remains a separate follow-up. A UI-framework
migration would not remove the demonstrated validity-mask scan.

Commands, marker semantics and limitations: [diagnostic guide](INTERACTION_LATENCY.md).
