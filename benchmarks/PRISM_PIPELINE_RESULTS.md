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

### Sample-to-GC diagnostic instrumentation (2026-10-29)

The [diagnostic protocol](PRISM_PIPELINE_BENCHMARK.md#sample-to-gc-correlation-diagnostics-p17)
now provides per-request JFR boundaries, a bracketed monotonic/JFR clock bridge,
and an analyzer that joins those events to the matching successful benchmark
CSV before reporting overlaps with outer stop-the-world GC pauses. Snapshot
copy intervals are separate from request-to-first-visible intervals.

The initial restricted-shell attempt at the planned three-pair graphical
diagnostic run did not collect measurements: the first ES2 JVM loaded its native library but `MacGLFactory` failed to
initialize, ending with `No toolkit found` before any sample. The restricted
execution environment did not provide a working ES2 context; desktop Terminal
access was also unavailable through the automation tool. This is invalid
execution evidence, not a pipeline timing result or a demonstrated defect in
the new diagnostic instrumentation. Raw startup logs remain private.

That attempt left the allocation/GC explanation `inconclusive`. No production
optimization or default-pipeline change was introduced and no historical result
was relabelled. The separate desktop run below subsequently collected real-frame
correlations; it does not turn the failed shell attempt into valid evidence.

### Desktop sample-to-GC correlation (diagnostic, 2026-10-29)

Executed the predeclared three-pair budget in IntelliJ IDEA's active macOS
desktop terminal, with `caffeinate -d -i -u --`. ES2 and Metal initialized
successfully there, establishing an execution-environment boundary for the
restricted-shell startup failure above. Order: ES2/Metal, Metal/ES2, ES2/Metal.
The build was frozen for all six JVMs; every compiled-file hash matched before
and after the run. Measured candidate identity (including the diagnostic source
changes, not reproducible from the base revision alone):

```yaml
baseRevision: ffeb29e62debc0ac3cf4bbf1b57afe6802a4fdc2
mergeBase: ffeb29e62debc0ac3cf4bbf1b57afe6802a4fdc2
contentId: sha256:e86f819dca1ee1af433bae32b4cd764659769f00dc6e1f3dc9184d30682cc2a1
```

Environment: Apple M3 Pro, 12 physical cores, 18 GiB memory, macOS 27.2 ARM64,
JDK 26.0.1+8-34, JavaFX 26.0.2+3, output scale 2x, 4 GiB maximum heap. Workload:
`mandelbrot-overview`, `mandelbrot-aa-regular`, `pan-25`, at 1512x982 and
3024x1964, FAST then REFINED, three warmups and three measured sequences after
each cold sequence. All 12 before/after observations reported AC power and
nominal thermal pressure. No competing build or test was launched during the
series; unrelated desktop activity was not independently monitored.

Each JVM recorded 112 requests including cold/warmup work and 48 measured
requests. All six completed the same-grid CPU sample/ARGB conformance checks;
all paired sample/ARGB fingerprints matched exactly for every request. The
analyzer matched every JFR request to its CSV identity and timing boundaries;
no missing requests. It correlated 288 measured requests in total. The maximum
clock-anchor bracket among measured requests was 2.916 microseconds. There
were no planning intervals with possible-but-not-definite GC overlap.

The table reports every overview group, nine measured samples per row across
three JVMs. Times are descriptive pooled medians in milliseconds; no confidence
interval, timing verdict or new pipeline ranking is inferred from this budget.
GC counts mean samples with at least one definite outer `GCPhasePause` overlap.

| Size | Requested mode | Pipeline | Planning ms | First visible ms | Planning GC samples | Snapshot GC samples |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| 1512x982 | Fast | ES2 | 0.272 | 1.581 | 1/9 | 2/9 |
| 1512x982 | Fast | Metal | 0.692 | 2.079 | 0/9 | 2/9 |
| 1512x982 | Refined | ES2 | 0.255 | 1.057 | 0/9 | 2/9 |
| 1512x982 | Refined | Metal | 0.230 | 0.980 | 0/9 | 2/9 |
| 3024x1964 | Fast | ES2 | 0.720 | 2.585 | 1/9 | 0/9 |
| 3024x1964 | Fast | Metal | 0.664 | 2.117 | 1/9 | 0/9 |
| 3024x1964 | Refined | ES2 | 0.633 | 1.983 | 1/9 | 0/9 |
| 3024x1964 | Refined | Metal | 2.340 | 4.166 | 1/9 | 0/9 |

For the historically failing 1512x982 Refined group, no measured request under
either pipeline had even a possible GC pause overlap before first visible
publication. Two ES2 planning samples exceeded 0.5 ms (0.959 and 0.701 ms),
with no GC overlap; no Metal planning sample exceeded 0.5 ms. Each pipeline
instead had two GC-overlapping snapshots after timing had ended. Thus this run
does not reproduce the historical failure or confirm its proposed GC cause.
The untimed snapshot GC pauses must not be attributed to first-visible latency.

The larger frame demonstrates that GC can fall inside planning without
accounting for the whole delay. For 3024x1964 Refined, the single GC-overlapping
planning sample was 7.661 ms on ES2 with 1.786 ms of overlapping pause, and
6.214 ms on Metal with 1.424 ms of overlapping pause. For the eight samples
without planning GC overlap, median planning was still 0.620 ms (ES2) and
2.337 ms (Metal). Excluding samples is a diagnostic partition only; it does not
produce a corrected performance verdict. The remaining time is not attributed
to a particular allocator, zeroing, scheduling or concurrent-GC mechanism by
these measurements.

Decision: sample-to-pause correlation is now exercised on real frames, but the
GC explanation for the historical failing group remains `inconclusive`. A GC
pause alone does not explain every slow planning sample. Do not start a broad
frame-storage optimization on the assumption that this cause has been proven.
A further investigation should distinguish allocation/initialization work from
other stalls inside planning, using a separately declared diagnostic budget.
The three-pair budget is complete and is not extended to obtain a different
outcome. Both historical publication `fail` results and all outstanding timing,
Spaces, packaging and platform gates remain unchanged. Raw recordings, CSVs,
launch records, compiled-file hashes and the working analysis remain private;
only this sanitized summary is selected here, so a public checkout cannot
replay its raw correlations.


### Fresh-frame planning decomposition (diagnostic, 2026-10-29)

Executed the [separately predeclared decomposition budget](PRISM_PIPELINE_BENCHMARK.md#fresh-frame-planning-decomposition-diagnostic-p17):
three ES2/Metal process pairs in AB/BA order, six JVMs on one frozen build,
the same two sizes, three fixtures, FAST/REFINED order, three warmups and three
measured sequences, 4 GiB heap. Environment remained Apple M3 Pro (12 physical
cores, 18 GiB), macOS 27.2 ARM64, JDK 26.0.1+8-34, JavaFX 26.0.2+3, 2x output
scale, active desktop terminal with `caffeinate`. All 12 before/after power and
thermal observations were AC/nominal. No competing build or test was launched;
unrelated desktop activity was not independently monitored.

Measured candidate (before adding this result summary):

```yaml
baseRevision: ffeb29e62debc0ac3cf4bbf1b57afe6802a4fdc2
mergeBase: ffeb29e62debc0ac3cf4bbf1b57afe6802a4fdc2
contentId: sha256:d85cb89a29b9ef816bfb08ad4ada1890e03ac23d64ced2fa9a0f18af1b9252c5
```

The private JFR configuration was generated from this JDK's `profile.jfc` with
the protocol's constructor, allocation and safepoint settings. Its SHA-256 is
`6e3807cb2e317f8737ff1e3c7824a580c0822ad662b2c6aa89b844b2ca4275dc`. Both it and every
compiled-file hash were unchanged across the six JVMs. This configuration
instruments constructor execution and changes allocation/timing; the samples
must not be treated as unprofiled pipeline-performance measurements.

All actual pipelines, 112 request identities per JVM, same-grid CPU conformance
and paired sample/ARGB fingerprints passed. Of 288 measured requests, 216
created fresh frames and provided the new subintervals. Every such interval
matched exactly one `FractalData` and one `ValidityMask` constructor trace on
the recorded constructing thread. Reuse requests remained on their original
path and are not represented as zero-cost creations. Thread CPU accounting was
available. The largest clock-anchor bracket among fresh measured samples was
2.250 microseconds.

Each recording contained two unmatched safepoint beginnings. The early one
preceded the first frame and was bounded by the next safepoint beginning,
also before the first frame; the late one followed the last frame. Their
possible intervals miss every fresh-frame window, including cold/warmup work.
Other begin/end events were paired by ID. Safepoint envelopes are not added to
GC overlap, which can describe the same stop.

All overview groups are shown below. Each row pools nine measured samples
across three JVMs; columns are independent descriptive medians in milliseconds,
not additive stage budgets. `Frame` covers storage, mask and factory overhead;
constructor times include their trace instrumentation. `GC` counts samples
with a definite GC overlap in frame creation.

| Size | Requested mode | Pipeline | Grid ms | Frame ms | FractalData ms | Mask ms | Frame CPU ms | GC |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1512x982 | Fast | ES2 | 0.090 | 0.952 | 0.938 | 0.005 | 0.942 | 2/9 |
| 1512x982 | Fast | Metal | 0.085 | 0.866 | 0.836 | 0.006 | 0.863 | 1/9 |
| 1512x982 | Refined | ES2 | 0.072 | 0.921 | 0.896 | 0.013 | 0.919 | 0/9 |
| 1512x982 | Refined | Metal | 0.077 | 0.850 | 0.826 | 0.010 | 0.845 | 0/9 |
| 3024x1964 | Fast | ES2 | 0.052 | 0.748 | 0.637 | 0.008 | 0.631 | 4/9 |
| 3024x1964 | Fast | Metal | 0.033 | 1.390 | 1.364 | 0.009 | 0.688 | 2/9 |
| 3024x1964 | Refined | ES2 | 0.042 | 0.638 | 0.585 | 0.008 | 0.584 | 3/9 |
| 3024x1964 | Refined | Metal | 0.031 | 0.571 | 0.554 | 0.008 | 0.570 | 0/9 |

The 3024x1964 Refined `FractalData` constructor medians by process pair were:

| Pair | ES2 ms | Metal ms |
| --- | ---: | ---: |
| 0 | 0.585 | 0.554 |
| 1 | 0.517 | 0.539 |
| 2 | 0.641 | 3.480 |

The last Metal process contained two slow creations: frame wall times of
3.496 and 3.895 ms, with FX-thread CPU times of 3.494 and 3.894 ms. Their
`FractalData` traces were 3.480 and 3.878 ms; grid creation was 0.026 and
0.034 ms and the mask was about 0.008 ms. Neither creation had even a possible
GC or safepoint-envelope overlap. These samples localize the time to CPU work
inside the sample-storage constructor, rather than a stopped or descheduled
FX thread, grid mathematics, or validity-mask construction. This is evidence
for these profiled samples, not proof of the cause of a historical unprofiled
failure or a claim that all scheduling delay is measurable with these counters.

For every overview frame, outside-TLAB allocation events on the same thread
identified the three primitive sample arrays at the `FractalData` constructor:
19,302,240 bytes at 1512x982 and 77,208,816 bytes at 3024x1964, including array
headers/alignment. No orbit-trap distance plane was allocated for these
fixtures. The constructor's remaining source work is scalar initialization;
allocation/initialization is therefore the identified CPU-work path. The
point events do not split allocator time, implicit array clearing, native
memory/page-fault work or instrumentation cost. Their timestamps must not be
reported as individual allocation durations. Mask arrays may fit inside a
TLAB, so absence of an outside-TLAB mask event is not absence of allocation.

GC still explains some other creation outliers in this run. For example, a
3024x1964 Refined ES2 sample took 1.487 ms with 0.615 ms of FX CPU time and a
0.830 ms overlapping GC pause. The CPU-heavy Metal samples above demonstrate
that this is not the only mechanism. No independent medians were subtracted to
claim a GC-corrected performance result.

Decision: the profiled slow path is localized to `FractalData` sample-array
allocation/initialization. This supports designing a bounded, cancellable
experiment to prepare fresh storage outside the FX thread; no such production
change is included or promoted here. The exact allocator/clearing/page-fault
cause and the historical performance-gate failure remain `inconclusive`.
Do not infer a speedup from constructor tracing, pool these samples with the
previous series, or extend the completed budget to obtain a preferred result.
Both historical publication failures, the pending screen-latency confirmation
and every remaining migration gate stay unchanged. Raw recordings, generated
configuration, source patch, launch records, hashes and analysis remain private;
only this sanitized summary is selected for the public tree.


### Bounded worker preparation experiment (2026-10-29)

Ran the [predeclared worker policy](policies/p17-fresh-frame-preparation.json)
through the [benchmark-only preparation experiment](PRISM_PIPELINE_BENCHMARK.md#bounded-worker-preparation-experiment-p17).
A uses the existing FX factory path; B creates fresh frames on one bounded
worker and returns to FX for setup. Both use the same frozen compiled build and
`MTLPipeline`. This compares preparation strategies on Metal, not ES2 versus
Metal, and does not change the application controller or pipeline default.

Measured candidate source:

```yaml
baseRevision: ffeb29e62debc0ac3cf4bbf1b57afe6802a4fdc2
mergeBase: ffeb29e62debc0ac3cf4bbf1b57afe6802a4fdc2
contentId: sha256:c4b6d6c92eacc4add6e3e5318d6a3091564c5314b22ee4d27faad19557a0520e
```

The policy was retained separately as a frozen input, SHA-256
`1d32898748d0747daac61a1d5a77f262ec4db25e2770d7dae12a51797684c12d`. The source patch, compiled/dependency/JDK hashes and raw records
remain private. All before/after build hashes and the policy hash matched.
The result-summary and selected-policy Git ignore exception were added after
measurement; they are not represented as measured source changes.

Environment: Apple M3 Pro, 12 physical cores, 18 GiB memory, macOS 27.2 ARM64,
JDK 26.0.1+8-34, JavaFX 26.0.2+3, output scale 2x, 4 GiB heap. Six AB/BA process
pairs, five measured sequences after three warmups and a cold sequence per
fixture/mode; sizes 1512x982 and 3024x1964; overview, regular AA and pan-25;
FAST then REFINED. JFR, constructor tracing, scheduling profiling and GPU
calculation were disabled. Execution used the active desktop terminal with
`caffeinate`; all 24 before/after observations were AC power and nominal thermal
pressure. No competing task-owned build/test/measurement ran; unrelated desktop
activity was not independently monitored.

All 12 JVMs exited successfully. Each contained 144 requests including
cold/warmup work and 80 measured requests, for 960 measured requests overall.
Every paired request identity and sample/ARGB fingerprint matched; last-sequence
same-grid CPU conformance passed in every fixture/mode. The dedicated analyzer
validated every declared timing group with 30 matched observations across six
process pairs, without profiling, missing samples or fallback pipelines.

**Decision: `reject`.** Timing alone was `inconclusive` (46 of 84 groups pass,
38 inconclusive, none fail), and the separately predeclared RSS cap failed.
The complete timing-group breakdown is:

| Metric / role | Groups | Pass | Inconclusive | Fail |
| --- | ---: | ---: | ---: | ---: |
| `fx_setup_ms` / target | 12 | 11 | 1 | 0 |
| `fx_setup_ms` / control | 4 | 1 | 3 | 0 |
| `first_visible_publish_ms` / control | 16 | 4 | 12 | 0 |
| `full_publish_ms` / control | 16 | 12 | 4 | 0 |
| `first_post_layout_ms` / control | 16 | 3 | 13 | 0 |
| `full_post_layout_ms` / control | 16 | 13 | 3 | 0 |
| `first_aa_tile_ms` / control | 4 | 2 | 2 | 0 |

Every fresh-frame target requires an upper 95% ratio bound <= 0.90; every
control requires <= 1.05. The sole inconclusive fresh-frame target was
3024x1964 overview Refined. The table below shows every overview FX-work target
and every overview first-visible control. Times are descriptive medians in ms;
ratios are medians of matched B/A samples, not ratios of these pooled medians.
Confidence intervals use the predeclared 5,000 seeded hierarchical resamples.
The full 84-group working analysis remains private; no group is removed from
the breakdown or decision above.

| Size | Requested mode | Metric | FX ms | Worker ms | B/A | 95% CI | Verdict |
| --- | --- | --- | ---: | ---: | ---: | --- | --- |
| 1512x982 | Fast | `first_visible_publish_ms` | 1.447 | 1.728 | 1.032 | 0.924–1.084 | inconclusive |
| 1512x982 | Fast | `fx_setup_ms` | 0.624 | 0.303 | 0.389 | 0.300–0.573 | pass |
| 1512x982 | Refined | `first_visible_publish_ms` | 0.947 | 0.986 | 1.075 | 0.933–1.136 | inconclusive |
| 1512x982 | Refined | `fx_setup_ms` | 0.433 | 0.224 | 0.569 | 0.502–0.599 | pass |
| 3024x1964 | Fast | `first_visible_publish_ms` | 5.914 | 2.632 | 0.522 | 0.424–0.841 | pass |
| 3024x1964 | Fast | `fx_setup_ms` | 4.997 | 1.150 | 0.226 | 0.123–0.323 | pass |
| 3024x1964 | Refined | `first_visible_publish_ms` | 5.107 | 6.396 | 1.114 | 0.466–3.008 | inconclusive |
| 3024x1964 | Refined | `fx_setup_ms` | 4.681 | 1.543 | 0.443 | 0.098–1.278 | inconclusive |

The memory rule was an observed per-pair cap: every B/A peak RSS ratio <= 1.05.
Peak RSS comes from `wait4` for the entire child JVM, including setup, cold and
warmup work, rendering and conformance. It is not live-frame bytes or a memory
leak measurement. Every pair is shown, including the initially lower worker
peaks and both cap violations:

| Pair / order | FX peak MiB | Worker peak MiB | B/A | Cap verdict |
| --- | ---: | ---: | ---: | --- |
| 0 / AB | 1990.8 | 1533.6 | 0.7703 | pass |
| 1 / BA | 1865.5 | 1780.6 | 0.9545 | pass |
| 2 / AB | 1813.6 | 1731.8 | 0.9549 | pass |
| 3 / BA | 1837.2 | 1932.7 | 1.0520 | fail |
| 4 / AB | 1818.1 | 1991.8 | 1.0956 | fail |
| 5 / BA | 1829.2 | 1907.8 | 1.0429 | pass |

Pairs 3 and 4 exceeded the observed RSS budget by showing increases of about
5.2% and 9.6%. There is no recorded basis here for attributing the higher peak
to a particular GC/heap-commit or native-memory mechanism, and no claim that
this is a persistent leak. It nevertheless fails the declared experiment cap;
the valid run is not reclassified as an environment failure or rerun to obtain
a preferable ratio. All groups and pairs remain in the result.

Developer correctness checks separately passed eight deterministic preparation
lifecycle tests, 12 JavaFX driver tests on Metal and the same 12 on ES2, plus
an all-fixture worker smoke at 96x66 in FAST/REFINED modes. They cover actual
allocation drain on cancel, latest-request coalescing under blocked allocation,
ready-slot replacement with delayed delivery, close before publication,
exception handling, FX queue responsiveness and exact fresh/reused pixel
output. The small smoke does not establish realistic-size latency; the paired
campaign above supplies that scoped evidence. Application-level generation,
resize/scene-transition, cache/AA and platform acceptance are not claimed by a
benchmark-only implementation.

Keep the FX control as the default. The worker implementation is retained only
as an explicit test-source experiment for reproducibility and regression tests;
it is not wired into production and is not an accepted optimization. Lower FX
setup work in most groups did not satisfy the joint latency/memory contract.
No benchmark limit is relaxed and no extra process pairs are appended. Any
future candidate needs a new declared budget and its own correctness, gesture
latency and memory checks. P1.7's historical pipeline failures, pending screen
latency campaign and remaining migration gates are unchanged. Raw logs, CSVs,
launch/resource records, frozen inputs and the full analysis remain private;
only this sanitized summary and the explicit policy are selected for publication.


Post-review tooling correction (no new timing campaign): the analyzer now checks
the full declared fixture/size/step product against the canonical manifest,
including step actions. A regression removes one fixture/size from every JVM's
manifest and samples and requires rejection; an additional check covers a
missing pan step. Revalidating the preserved original campaign confirms all
84 groups, 2,520 matched metric observations and six RSS pairs. Its statistics,
policy/source identities and `reject` decision above remain unchanged.

A public [preparation runner](../scripts/benchmarks/run_frame_preparation.py)
now produces the analyzer's build-before/build-after snapshots, launch/resource
records and completion marker. Its complete orchestration is tested against
the real analyzer using synthetic child outputs; its headless preflight was
also exercised against the preserved byte-identical experiment binaries. The
historical campaign used the private working runner, not this later public
implementation. No new measured campaign was run with the public runner in
this correction. Fresh-run and preflight commands are in the protocol; the
historical raw archive remains private.

## Limits

- One Mac model; results do not transfer to Intel Macs or other GPUs, and
  Windows and Linux do not use Metal.
- Screen observation is a JavaFX Robot capture proxy, not physical scanout.
- The Space-switching defect that motivated the comparison was checked
  manually, not by this benchmark.
