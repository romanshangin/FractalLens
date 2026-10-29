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

## Confirmation campaign (P1.7, 2026-10-28)

Run with the [predeclared confirmation campaign](PRISM_PIPELINE_BENCHMARK.md#predeclared-confirmation-campaign-p17)
on the same Mac, macOS 27.2, Java 26.0.1 and JavaFX 26.0.2. One frozen build of
revision `f8895b6` (build identity `c5a00176…aec5`) ran under both pipelines;
every A log confirmed `ES2Pipeline` and every B log `MTLPipeline`. All 240
before/after observations recorded AC power and nominal thermal state. The
runner was prefixed with `caffeinate -d -i -u --`.

An earlier attempt of the publication campaign was stopped by its process
timeout after 56 of 60 pairs. It is incomplete, kept privately as invalid
evidence and is not part of this result; the campaign below was rerun in full
in a new output directory.

### Publication pairs

Policy [`prism-pipeline-fx-confirm.json`](policies/prism-pipeline-fx-confirm.json),
60 AB/BA process pairs, 180 matched samples per group, no missing pairs.
Verdict: `fail`, 19 of 28 groups pass, 8 are inconclusive and 1 fails.

| Window | Fixture | Mode | Metric | ES2 ms | Metal ms | Ratio | 95% CI | Verdict |
| --- | --- | --- | --- | ---: | ---: | ---: | --- | --- |
| 3024x1964 | overview | Refined | first visible publish | 6.61 | 11.25 | 1.341 | 1.107–1.720 | fail |
| 1512x982 | aa-regular | Refined | first AA tile | 1.92 | 2.06 | 1.124 | 1.047–1.464 | inconclusive |
| 3024x1964 | overview | Fast | first visible publish | 9.68 | 10.19 | 1.053 | 1.018–1.250 | inconclusive |
| 3024x1964 | overview | Fast | full publish | 58.30 | 60.06 | 1.028 | 1.006–1.060 | inconclusive |
| 3024x1964 | overview | Fast | full post-layout | 58.44 | 60.11 | 1.027 | 1.006–1.060 | inconclusive |
| 3024x1964 | overview | Refined | full publish | 59.51 | 63.38 | 1.020 | 0.983–1.055 | inconclusive |
| 3024x1964 | overview | Refined | full post-layout | 61.07 | 63.53 | 1.022 | 0.982–1.059 | inconclusive |
| 1512x982 | pan-25 | Fast | first post-layout | 9.48 | 9.91 | 1.052 | 1.025–1.063 | inconclusive |
| 1512x982 | pan-25 | Refined | first post-layout | 9.29 | 9.54 | 1.037 | 1.017–1.050 | inconclusive |
| 3024x1964 | pan-25 | Fast | full post-layout | 50.84 | 45.91 | 0.957 | 0.931–0.985 | pass |
| 3024x1964 | pan-25 | Refined | full post-layout | 50.85 | 45.37 | 0.957 | 0.921–0.979 | pass |

The table lists all 9 non-passing groups and the two largest improvements; the
other 17 groups pass. The failing group reproduces the first campaign's
3024x1964 Refined outlier with a larger budget: per-process median ratios range
from 0.15 to 9.76, above 1 in 36 and above 2 in 19 of 60 process pairs, so the slow Metal
first visible publish is frequent rather than an occasional pair. The 1512x982
pan step remains about 0.3–0.4 ms slower under Metal. These metrics end on the
FX thread and do not show where the delay originates.

### Screen latency pairs

Not executed. One JVM of
[`prism-pipeline-latency-confirm.json`](policies/prism-pipeline-latency-confirm.json)
took 366 s, so the 30-pair campaign needs about 6 hours, above the 3-hour
process timeout set for this run. The attempt was stopped during its second JVM,
before any pair completed, and is kept privately as invalid evidence.

### Decision

The publication campaign has a failing group. Under the predeclared rule this
rejects default Metal activation under the performance gate, independent of the
screen-latency result. This is a timing result for one Mac; it does not change
the Space-switching motivation, and any activation despite it would need a new
owner decision that records the trade-off without relabeling the result.

### Failing group diagnosis (diagnostic)

Run after the campaign on the same Mac and frozen build, with the campaign's
launch commands plus diagnostic JVM flags. These runs explain the failing
group; they are not verdicts and do not change the `fail` label.

Campaign samples (180 per pipeline, 3024x1964 overview, Refined) locate the
delay before the backend starts:

| Interval | ES2 median ms | Metal median ms |
| --- | ---: | ---: |
| FX dispatch | 0.02 | 0.02 |
| Frame planning (`plan_ms`) | 4.71 | 8.65 |
| First backend region | 6.42 | 11.16 |
| First visible publish | 6.61 | 11.25 |
| Backend render | 39.10 | 38.53 |

First backend region to first visible publish takes about 0.1–0.2 ms under
both pipelines. For this fixture `plan_ms` covers only the allocation of a
fresh `RenderFrame` on the FX thread; no Prism code runs in it. It is bimodal,
about 2 ms or 7–16 ms, and the slow mode follows heap occupancy before the
sample: below about 400 MiB it stays near 1.9 ms under both pipelines, above
800 MiB it is 8.5 ms (ES2) and 11.4 ms (Metal). Heap occupancy before the
sample is higher under Metal (median 980 → 1226 MiB in Refined, 794 → 1021 MiB
in Fast). Slow allocations occur mostly in samples without a GC: in ES2 Fast,
111 of 141 samples without a GC and 6 of 39 with a GC are slower than 4 ms.

Six JFR JVMs per pipeline (`settings=profile`, alternating order) show the same
Java allocation profile under both pipelines (`int[]` about 34%, `double[]`
24%, `FractalSample` 24–26%, the same per-thread split) and similar GC pause
medians (about 0.9 ms for humongous-allocation pauses). Metal JVMs had fewer
humongous-allocation collections (504 against 612). On the FX thread, 45 ES2
and 44 Metal execution samples fall inside `RenderFrame.create`. Of these, 43
ES2 and 42 Metal samples are in `Arrays.fill` called from the `FractalData`
constructor, which fills the full-size orbit-trap distance array with `NaN`;
the remaining two per pipeline are in `RenderGrid` construction, including
`BigDecimal` operations. Sampling cannot separate the
fill itself from page faults on newly committed memory that the fill touches.

The campaign JVMs use the default initial heap, 8 MiB, which grows to its
4 GiB limit in 9–25 capacity steps per JVM, with no systematic difference
between pipelines. Two controls with six JVMs per pipeline and 18 samples per
group separate the fixed heap size from pre-touching:

| Mode | Metric | `-Xms4g` ES2 / Metal ms | `-Xms4g -XX:+AlwaysPreTouch` ES2 / Metal ms |
| --- | --- | --- | --- |
| Refined | frame planning | 2.41 / 2.16 | 2.11 / 2.20 |
| Refined | first visible publish | 3.87 / 3.57 | 3.58 / 3.54 |
| Fast | frame planning | 7.20 / 5.94 | 5.29 / 5.29 |
| Fast | first visible publish | 10.11 / 7.72 | 7.83 / 7.75 |

A fixed heap size alone removes the Refined difference and its slow tail;
pre-touching adds no measurable Refined change. Fast frame planning stays at
5–7 ms with wide spread in both controls and does not favor ES2.

The failing group is therefore localized to frame preparation on the FX
thread, the allocation and `NaN` fill of a fresh full-size frame, and depends
on the JVM heap-sizing state rather than on the Prism publication path. The
mechanism is not established: first-touch cost of memory committed during heap
growth is consistent with these runs but remains a hypothesis, as does the
reason the heap state differs under Metal. The controls are diagnostics, not a
protocol change. The product path also prepares a fresh frame on the FX
thread, so preparing it off the FX thread is a candidate change that would
need its own measurement, including cancellation, generation and memory
checks.

### Frame preparation change (diagnostic, 2026-10-28)

`FractalData` now allocates the orbit-trap distance plane only when the
frame's formula has an orbit trap or is a custom formula without that
guarantee; built-in presets without a trap report `NaN` without the
full-size `NaN`-filled array. At 3024x1964 this removes about 47 MB of
allocation and the `Arrays.fill` from every fresh frame prepared on the FX
thread. A first variant that allocated the plane lazily on the first finite
distance made FX-thread coloring about 16 ms slower per frame because every
pixel read checked a volatile field; it was replaced by the construction-time
decision recorded here.

Six alternating JVMs per variant with the campaign launch commands and
default heap, comparing the frozen `f8895b6` build with the changed working
tree (18 samples per group; diagnostic, not a protocol verdict):

| Mode | Metric | Old ES2 | Old Metal | New ES2 | New Metal |
| --- | --- | ---: | ---: | ---: | ---: |
| Refined | frame planning, median ms | 2.76 | 14.61 | 0.66 | 1.26 |
| Refined | first visible publish, median ms | 4.23 | 17.38 | 2.13 | 2.48 |
| Refined | first visible publish, p90 ms | 28.01 | 28.15 | 11.41 | 12.38 |
| Fast | first visible publish, median ms | 4.64 | 11.22 | 3.15 | 5.96 |
| Refined | full publish, median ms | 56.26 | 70.73 | 50.00 | 51.92 |

FX-thread coloring work is unchanged (about 24–25 ms for the overview,
1.5 ms for the pan step), and ARGB hashes match across all four variants in
all 16 measured groups. The heap-dependent slow mode of the failing group is
much rarer, but frame planning still varies with heap state because the
remaining sample arrays are allocated per frame, and Fast first visible
publish remains slower under Metal in this small run. Whether the timing gate
now passes needs a new predeclared paired campaign; the `fail` above stands.

### Post-change publication campaign (P1.7, 2026-10-28)

Run with the [predeclared post-change campaign](PRISM_PIPELINE_BENCHMARK.md#predeclared-post-change-publication-campaign-p17)
on the same Mac, macOS 27.2, Java 26.0.1 and JavaFX 26.0.2. One frozen build of
revision `2a19472` ran under both pipelines; every A log confirmed
`ES2Pipeline` and every B log `MTLPipeline`. All 240 before/after observations
recorded AC power and nominal thermal state. The runner was prefixed with
`caffeinate -d -i -u --` and ran through the private check registry with a
10800 s timeout.

Policy [`prism-pipeline-fx-confirm.json`](policies/prism-pipeline-fx-confirm.json),
60 AB/BA process pairs, 180 matched samples per group, no missing pairs.
Verdict: `fail`, 25 of 28 groups pass, 2 are inconclusive and 1 fails.

| Window | Fixture | Mode | Metric | ES2 ms | Metal ms | Ratio | 95% CI | Verdict |
| --- | --- | --- | --- | ---: | ---: | ---: | --- | --- |
| 1512x982 | overview | Refined | first visible publish | 1.03 | 1.13 | 1.119 | 1.064–1.243 | fail |
| 3024x1964 | overview | Refined | first visible publish | 2.29 | 2.36 | 1.014 | 0.954–1.107 | inconclusive |
| 3024x1964 | overview | Fast | first visible publish | 3.10 | 2.94 | 0.988 | 0.863–1.074 | inconclusive |
| 1512x982 | overview | Fast | first visible publish | 2.08 | 2.25 | 0.894 | 0.834–0.960 | pass |
| 3024x1964 | aa-regular | Fast | first AA tile | 5.76 | 5.33 | 0.925 | 0.890–0.971 | pass |

The table lists all 3 non-passing groups and the two lowest ratios. The group
that failed the confirmation campaign is now inconclusive, with both medians
about 2.3 ms instead of 6.6 and 11.3 ms. Of the eight groups that were
inconclusive there, seven now pass (the 1512x982 first AA tile, both
1512x982 pan first post-layout steps and all four 3024x1964 overview
full-publication groups), and 3024x1964 Fast first visible publish stays
inconclusive. A different group now fails: 1512x982
Refined first visible publish, a difference of about 0.1 ms on a sub-1.5 ms
metric, which passed in the confirmation campaign.

Decision: the campaign has a failing group, so under the predeclared rule it
again rejects default Metal activation under the performance gate,
independent of the screen-latency result. The frame-preparation change
removed the large heap-dependent delay, but the gate, as declared, is not
passed. Any activation despite it needs an explicit owner decision that keeps
this result labeled `fail`.

### Post-change failing group diagnosis (diagnostic)

These runs explain the failing group; they are not verdicts and do not
change the `fail` label.

For the overview fixture, which has no antialiasing, requested Refined runs
with effective mode Fast, so the Fast and Refined overview groups measure the
same work and differ only in their position within each JVM; Fast runs first.
Campaign samples (180 per pipeline) for 1512x982 Refined first visible
publish:

| Interval, median ms | ES2 | Metal |
| --- | ---: | ---: |
| Frame planning (`plan_ms`) | 0.229 | 0.258 |
| Frame planning, p90 | 0.279 | 1.107 |
| Planned to first backend region | 0.655 | 0.691 |
| First backend region to first visible publish | 0.111 | 0.142 |
| First visible publish | 1.031 | 1.130 |

The difference sits mainly in the frame-planning tail. The per-sample GC
counter covers the whole measured operation, including the pixel snapshot
taken after the timed interval, so it shows that a collection happened during
the sample, not when. ES2 recorded a GC during 113 samples with unchanged
planning (median 0.227 ms); Metal recorded a GC during 87 samples, and their
planning median rose to 0.825 ms. Planning
exceeded 0.5 ms in 59 Metal and 7 ES2 samples. Without those slow samples,
first visible publish is 1.027 ms (ES2) and 1.057 ms (Metal). In the Fast
overview group, which runs first, slow planning occurred in 116 ES2 and 119
Metal samples.

Six alternating JVMs per pipeline on the frozen `2a19472` build, with the
campaign launch commands plus GC logging and JFR recording of outside-TLAB
allocations with stacks, attributed each `G1 Humongous Allocation` pause to
the outside-TLAB allocation that completed within 2 ms after it, a timing
heuristic rather than a recorded cause (213 of 360 ES2 and 164 of 298 Metal
pauses were attributed):

| Allocation site (thread) | ES2 pauses | Metal pauses | Median pause, ES2 / Metal |
| --- | ---: | ---: | --- |
| `FractalData` in `RenderFrame.create` (FX) | 75 | 49 | 0.84 / 0.91 ms |
| Benchmark pixel snapshot (FX, outside the timer) | 55 | 42 | 0.83 / 0.90 ms |
| `SurfaceBuffer` (FX) | 19 | 29 | 0.82 / 0.86 ms |
| Metal render-target texture (render thread) | — | 4 | — / 0.97 ms |

Under both pipelines, the most frequent attributed site of these pauses of
about 0.85–0.9 ms is the frame's sample arrays allocated on the FX thread,
which is consistent with the added planning time. Metal also allocates about 30 MB of
render-target arrays per JVM on the render thread, against about 10.7 GB
allocated outside TLABs by the FX thread; this may shift when collections
fall, but that and the link of individual pauses to individual samples of the
failing group remain inferred, because the benchmark records no timestamps.
Benchmark verification snapshots also allocate large arrays on the FX thread
outside the timed interval and shape the heap state for the next sample.

The most likely explanation of the remaining failure is therefore a GC pause
during FX-thread frame allocation rather than slower Metal publication. It is
not proven: confirming it would require correlating the timed interval of each
sample with pause timestamps, which the benchmark does not record. If it holds,
removing the failure would require not allocating full-size frame storage on
the FX thread for each render, a larger change with its own cancellation,
generation and memory checks.

## Limits

- One Mac model; results do not transfer to Intel Macs or other GPUs, and
  Windows and Linux do not use Metal.
- Screen observation is a JavaFX Robot capture proxy, not physical scanout.
- The Space-switching defect that motivated the comparison was checked
  manually, not by this benchmark.
