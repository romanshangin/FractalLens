# Interaction latency results

> Historical evidence: results and “current source” describe the dated experiment, not validation of this checkout. Only [selected evidence](README.md) is included; “private archive” entries are unavailable here. Original SHAs/commands may require private revisions or inputs. See [provenance and replay limits](../docs/development/PUBLICATION.md).

Measured 2026-09-06 on Apple M3 Pro, macOS 26.6.2, Java 26.0.2, JavaFX 26.0.2+3,
2 GiB maximum heap. Viewport: 1200x760 logical / 2400x1520 physical pixels.
The graphics smoke log confirmed the ES2 pipeline on Apple M3 Pro, with vsync enabled.
GPU fractal calculation was disabled. No timing run overlapped the test suite or another benchmark.

## Finding: cached navigation spends about 1.87 seconds planning no work

All 60 cached zoom trials deliver one base callback/no intermediate progress.
Submission→enqueue median 1861–1886 ms for wheel/burst/pinch; excludes controller
preparation/FX queue wait.

`ParallelFractalCalculator.createTileTasks` calls `ValidityMask.missingRowSpans`
per 32x32 tile even when full. Each `BitSet.nextClearBit(rowFrom)` scans past row/
region through ready suffix; query count/suffix length both grow with frame.

An isolated benchmark reproduced the delay using only the production validity mask
and tile partition. No JavaFX, fractal formula or AA was involved:

| Buffer | Production tile scan, median | Diagnostic complete-frame guard, median |
| --- | ---: | ---: |
| 1200x760 | 115.79 ms | 0.0092 ms |
| 2400x1520 | 1821.57 ms | 0.0328 ms |

4×pixels →15.7×scan, both paths zero spans. Guard is isolated, **not** production
or app speedup. Matching trace identifies JavaFX-independent cached planning;
partial-mask costs differ.

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

Preview transform median <1 ms. Fast exposes cached base before scan ends;
Refined waits AA, exposing delay. Trackpad: 77–82 ms initial wait/41–68 ms setup,
callback enqueue 4–5 ms after submit. Drag renders on release; ~23 ms includes
scripted pause, not separate drag debounce.

The normal delays are 30 ms for wheel and 75 ms for live pinch/trackpad. Pinch finish
flushes pending rendering; scroll finish currently does not flush pan. These are
application policies, not an intrinsic minimum imposed by JavaFX.

AA queues thousands of callbacks; single-wheel max queue median 29–36 ms is
secondary to 1.87-second planning. Overlapping waits are not additive wall time.

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

All five trials observed every listed marker. **Capture-based presentation proxies**,
not scanout/all-pixel inspection. Synthetic FX dispatch excludes hardware/OS input.

Intrusive FX Robot capture totals 1.72–1.88 seconds/wheel trial. Median maximum
probe gaps 34–55 ms, Refined up to 109 ms. Initial 32–42 ms/late publication
observations include sampling/capture, not pure FX/compositor latency; capture
also alters gesture spacing.

Wheel complete: Fast 2069.6 ms without/2078.5 with capture, Refined 2080.7/2132.5.
Pan/drag move inconsistently: no fixed correction. Precise photons need less
intrusive OS trace/external high-speed recording.

Screen raw (private archive: `INTERACTION_LATENCY_SCREEN.csv`), [screen summary](INTERACTION_LATENCY_SCREEN_SUMMARY.csv).

## Scope and validation

- The production rendering algorithm and interaction delays were not changed.
- The recorder is disabled in normal application runs. Enabled callbacks preserve
  recording/input/render identities; late work and observations cannot contaminate
  a new recording. Callback costs are aggregated rather than logging every tile.
- Portable suite: 359 tests, no failures/errors, 20 opt-in tests skipped. The visible
  benchmark runs additionally exercise actual view handlers, rendering and screen
  decoding.
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

First bound full/nearly-full mask scans and skip complete-frame work; preserve
exact reuse/cancel/partial behavior, rerun same interaction fixtures. Next evaluate
trackpad settle/finish and synchronous reuse/surface setup, then separate callback
batching. UI migration cannot remove validity scanning.

Commands, marker semantics and limitations: [diagnostic guide](INTERACTION_LATENCY.md).
