# Prism pipeline results: ES2 (OpenGL) and Metal

> Raw logs, CSV recordings, JFR files and per-pair data are kept in a private
> archive; this summary is the published evidence. See
> [provenance and replay limits](../docs/development/PUBLICATION.md).

Measured 2026-10-28 with the [Prism pipeline protocol](PRISM_PIPELINE_BENCHMARK.md)
on Apple M3 Pro (Mac15,7, 12 cores, 18 GiB), macOS 27.2, Java 26.0.1, JavaFX
26.0.2, AC power, thermal state nominal before and after every JVM. One frozen
build of revision `42569f1` (build identity `8a516e95…8474`) ran under both
pipelines; every log confirmed `ES2Pipeline` for A and `MTLPipeline` for B.
Ratios are B/A, Metal over ES2; the control limit is 1.05 for every metric.

## Decision

Both campaigns end `inconclusive`: 66 of 103 groups pass the 1.05 limit and
none fail, but the remaining 37 groups do not exclude a Metal slowdown beyond
5% at the declared sample sizes. Non-inferiority of Metal is therefore not
established. No group shows Metal slower beyond the limit, most screen-latency
groups favor Metal, and the render thread spends about five times less time
per pulse under Metal (diagnostic). The evidence does not block an evaluation
of Metal on macOS; a switch still needs the visible checks and the portable
suite listed in the protocol, and it would apply only to the measured Mac.

## Publication pairs

Policy [`prism-pipeline-fx.json`](policies/prism-pipeline-fx.json), 30 AB/BA
process pairs, 90 matched samples per group. Verdict: `inconclusive`, 18 of 28
groups pass, none fail.

| Window | Fixture | Mode | Metric | ES2 ms | Metal ms | Ratio | 95% CI | Verdict |
| --- | --- | --- | --- | ---: | ---: | ---: | --- | --- |
| 1512x982 | pan-25 | Fast | first post-layout | 9.47 | 9.94 | 1.059 | 1.013–1.074 | inconclusive |
| 1512x982 | pan-25 | Refined | first post-layout | 9.30 | 9.58 | 1.039 | 1.010–1.065 | inconclusive |
| 1512x982 | aa-regular | Refined | first AA tile | 1.92 | 2.09 | 1.120 | 1.046–1.515 | inconclusive |
| 1512x982 | overview | Refined | first visible publish | 1.48 | 1.53 | 1.017 | 0.909–1.100 | inconclusive |
| 3024x1964 | overview | Fast | first visible publish | 9.52 | 9.82 | 1.039 | 1.010–1.546 | inconclusive |
| 3024x1964 | overview | Refined | first visible publish | 4.35 | 9.02 | 1.491 | 0.982–2.153 | inconclusive |
| 3024x1964 | overview | Fast | full publish | 58.58 | 60.35 | 1.020 | 1.003–1.076 | inconclusive |
| 3024x1964 | overview | Fast | full post-layout | 58.69 | 60.39 | 1.021 | 0.994–1.079 | inconclusive |
| 3024x1964 | overview | Refined | full publish | 55.75 | 59.90 | 1.043 | 0.992–1.151 | inconclusive |
| 3024x1964 | overview | Refined | full post-layout | 55.93 | 60.00 | 1.039 | 0.996–1.151 | inconclusive |
| 3024x1964 | pan-25 | Fast | full post-layout | 51.35 | 45.65 | 0.929 | 0.903–0.955 | pass |
| 3024x1964 | pan-25 | Refined | full post-layout | 50.93 | 47.10 | 0.945 | 0.917–0.990 | pass |

The table lists all 10 non-passing groups and the two largest improvements. The
1512x982 pan step is a stable 4–6% (about 0.3–0.5 ms) slower first
post-layout pulse under Metal. The 3024x1964 overview has occasional Metal
process pairs whose first visible publish takes 3–8 times longer, while full
publication there stays within 2–4%. These metrics end on the FX thread, so
they do not show where the delay originates.

## Screen latency pairs

Policy [`prism-pipeline-latency.json`](policies/prism-pipeline-latency.json),
6 AB/BA process pairs, 5 samples, all three modes and five gestures, window
1200x760 logical at scale 2. Verdict: `inconclusive`, 48 of 75 groups pass,
none fail, no missing screen markers.

- Last input to complete frame on screen: ratios 0.92–1.00, all 15 groups pass
  (for example Fast drag 149.4 → 140.0 ms).
- Complete publication to screen: Metal is mostly 5–15% faster; 13 of 15
  groups pass (for example Base-only wheel burst 50.3 → 42.1 ms).
- Details publication to screen: 10 of 15 groups pass; the others have wide
  intervals around 1.
- First input to preview on screen: all 15 groups are inconclusive, with
  median ratios 0.94–1.04 and interval widths of about 0.2–0.45 at 30 pairs.
- Software-only completion after the last input: 10 of 15 pass, the rest
  have intervals straddling 1.

Earlier attempts of this campaign aborted on the driver's screen-visibility
guards while the computer was in use, before any complete campaign was
recorded; they are not part of the result. The successful run kept the
display awake with `caffeinate`.

## Pulse phases (diagnostic)

Two JFR JVM pairs per pipeline without screen capture, run after the timed
campaigns with explicitly enabled `javafx.PulsePhase` events. JFR changes
timing, so these numbers explain the timing results but are not verdicts.

| Thread | Phase | ES2 median / p95 ms | Metal median / p95 ms | ES2 total ms | Metal total ms |
| --- | --- | --- | --- | ---: | ---: |
| Render | Painting | 0.386 / 5.761 | 0.321 / 1.254 | 9095 | 2340 |
| Render | Presenting | 0.286 / 2.969 | 0.043 / 0.295 | 3028 | 552 |
| Render | Total per pulse | 2.649 / 6.603 | 0.525 / 1.553 | 12434 | 3200 |
| FX | Waiting for previous rendering | 0.001 / 0.005 | 0.001 / 0.004 | 357 | 42 |

Pulse counts were similar (4887 ES2, 5069 Metal). FX-thread phases (CSS,
layout, state copy, bounds update) are equal within a few percent.

## Limits

- One Mac model; results do not transfer to Intel Macs or other GPUs, and
  Windows and Linux do not use Metal.
- Screen observation is a JavaFX Robot capture proxy, not physical scanout.
- The Space-switching defect that motivated the comparison was checked
  manually, not by this benchmark.
