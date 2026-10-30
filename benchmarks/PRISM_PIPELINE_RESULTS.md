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

Both `inconclusive`: 66/103 pass 1.05, none fail, 37 cannot exclude >5% loss.
Non-inferiority unproven; no definite over-limit slowdown. Most screen groups
favor Metal; diagnostic pulse render work ~fivefold lower. Evaluation remains
reasonable, activation still needs protocol visible/portable gates, measured Mac only.

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

Table: 9 nonpasses/two biggest gains; 17 others pass. 3024x1964 Refined
failure repeats at larger budget: process medians 0.15–9.76, >1 in 36/>2 in
19 of 60 pairs, not rare. 1512x982 pan still ~0.3–0.4 ms slower. FX endpoints
do not locate delay.

### Screen latency pairs

Not executed. One JVM of
[`prism-pipeline-latency-confirm.json`](policies/prism-pipeline-latency-confirm.json)
took 366 s, so the 30-pair campaign needs about 6 hours, above the 3-hour
process timeout set for this run. The attempt was stopped during its second JVM,
before any pair completed, and is kept privately as invalid evidence.

### Decision

Publication failure rejects default under timing rule regardless of screen
result. One-Mac timing preserves Spaces motivation; activation needs owner
trade-off decision, never relabeling.

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

Backend→visible ~0.1–0.2 ms both. Here `plan_ms` is fresh FX `RenderFrame`
allocation, no Prism: bimodal ~2 or 7–16 ms. Pre-sample heap <400 MiB →~1.9 ms;
>800 →ES2 8.5/Metal 11.4 ms. Metal heap higher: Refined 980 →1226 MiB,
Fast 794 →1021 MiB. Most slow allocations have no GC: ES2 Fast >4 ms in
111/141 no-GC versus 6/39 GC samples.

Six alternating JFR JVMs/pipeline (`settings=profile`): same allocations/thread
split (`int[]` ~34%, `double[]` 24%, `FractalSample` 24–26%), humongous GC
median ~0.9 ms. Metal 504 collections versus ES2 612. FX samples in
`RenderFrame.create`: ES2 45/Metal 44; 43/42 in `Arrays.fill`/`FractalData`
full trap array `NaN`, two each in `RenderGrid`/`BigDecimal`. Sampling cannot
separate fill from first-touch page faults.

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

Delay localizes to FX fresh-frame allocation/`NaN` fill and heap-sizing state,
not Prism publication. First-touch during heap growth and differing Metal heap
state remain hypotheses. Diagnostic controls do not alter protocol. Production
also allocates on FX; worker preparation needs separate measurement/cancel/
generation/memory gates.

### Frame preparation change (diagnostic, 2026-10-28)

`FractalData` allocates trap plane for traps/custom formulas without no-trap
guarantee only; built-ins otherwise return `NaN` without array. At 3024x1964,
~47 MB/`Arrays.fill` removed per FX frame. Lazy first-finite variant added ~16 ms
coloring via per-pixel volatile reads; replaced by construction-time decision.

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

Color work unchanged (~24–25 ms overview/1.5 ms pan); ARGB matches four variants/
16 groups. Heap slow mode rarer, remaining per-frame arrays still vary planning;
Fast first-visible still slower Metal in small run. New predeclared campaign
needed; historical `fail` stands.

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

Table: all 3 nonpasses/two lowest ratios. Former failure now inconclusive,
medians ~2.3 ms versus 6.6/11.3. Seven of eight prior inconclusives pass:
1512x982 first AA, both 1512x982 pan first post-layout, four 3024x1964 full
publication. 3024x1964 Fast first remains inconclusive. New failure: 1512x982
Refined first, ~0.1 ms on <1.5 ms, previously passing.

Again `fail`: default activation rejected under declared performance rule,
independent of screen result. Large heap delay removed, gate still not passed;
owner exception must retain `fail` label.

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

Tail is mostly planning. Whole-operation GC includes post-timing snapshot,
proving collection but not placement. ES2 113 GC samples/0.227 ms planning;
Metal 87/0.825 ms. >0.5 ms planning: Metal 59/ES2 7; without these, first
visible 1.027/1.057 ms. Earlier Fast overview: slow planning ES2 116/Metal 119.

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

Both attribute ~0.85–0.9 ms pauses most often to FX sample arrays. Metal render
targets add ~30 MB/JVM on render thread versus ~10.7 GB outside TLAB on FX;
GC placement/link to failing samples is inferred without timestamps. Untimed FX
verification arrays also shape later heaps.

Likely FX-allocation GC, not slower publication, but unproven without sample/pause
timestamps. If confirmed, moving per-render full-frame allocation needs separate
cancel/generation/memory validation.

### Sample-to-GC diagnostic instrumentation (2026-10-29)

[Diagnostic protocol](PRISM_PIPELINE_BENCHMARK.md#sample-to-gc-correlation-diagnostics-p17)
adds request JFR, bracketed monotonic bridge and matching-successful-CSV joins to
outer STW pauses; snapshot/first-visible intervals remain separate.

Restricted first three-pair attempt collected nothing: ES2 native loaded,
`MacGLFactory` initialization failed/`No toolkit found` before samples. No working
restricted ES2 context; desktop Terminal automation unavailable. Invalid evidence,
not timing/instrumentation-defect proof. Startup logs private.

That attempt leaves cause `inconclusive`; no production/default change or relabeled
history. Later desktop correlations do not validate the failed shell execution.

### Desktop sample-to-GC correlation (diagnostic, 2026-10-29)

Three predeclared pairs in IDEA active macOS terminal/`caffeinate -d -i -u --`:
ES2/Metal, Metal/ES2, ES2/Metal. Both initialize, locating shell failure to environment.
Six frozen JVMs, before/after hashes match. Identity includes working diagnostic
changes, not reconstructible from base alone:

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

Per JVM 112 requests/48 measured; all six exact same-grid CPU/paired ARGB pass.
JFR/CSV IDs/boundaries fully matched, 288 measured total, max anchor bracket
2.916 microseconds; no possible-only planning GC overlap.

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

Historical 1512x982 Refined: no possible pre-first-visible GC either pipeline.
ES2 planning 0.959/0.701 ms exceed 0.5 without GC; Metal none >0.5. Each pipeline
has two post-timing GC snapshots. Failure/cause not reproduced; snapshots cannot
explain measured first-visible.

3024x1964 Refined GC-overlap sample: ES2 planning 7.661 ms/pause 1.786;
Metal 6.214/1.424. Eight no-overlap samples still median 0.620/2.337 ms.
Partition is diagnosis, not corrected verdict; residual allocator/zeroing/scheduling/
concurrent-GC cause unassigned.

Real-frame correlation works, historical GC cause remains `inconclusive`; not
all planning stalls are GC. No broad storage optimization on presumed cause.
New separate budget should isolate allocation/initialization from other waits.
Three-pair budget complete, no outcome-driven extension. Both historical publication
`fail`, outstanding timing/Spaces/packaging/platform gates unchanged. Raw JFR/CSV/
launches/hashes/analysis private; only sanitized summary public, not replayable raw.

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

Private JFR from JDK `profile.jfc` plus constructor/allocation/safepoint settings,
SHA-256 `6e3807cb2e317f8737ff1e3c7824a580c0822ad662b2c6aa89b844b2ca4275dc`.
Config/classes unchanged across six JVMs. Constructor profiling alters timing/
allocations; not unprofiled pipeline evidence.

Actual pipelines/112 IDs per JVM/exact CPU/paired hashes pass. Of 288 measured,
216 fresh frames provide subintervals, each matching one `FractalData`/`ValidityMask`
trace on constructing thread. Reuse stays original, not zero-cost creation.
CPU timer available; max fresh anchor bracket 2.250 microseconds.

Two unmatched safepoint starts/recording: early bounded by next start, both
before first frame; late after last. Possible intervals miss all fresh/cold/warm
frames. Pair others by ID; never add safepoint and overlapping GC stops.

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

Last Metal has two slow frames: wall 3.496/3.895 ms, FX CPU 3.494/3.894,
`FractalData` 3.480/3.878, grid 0.026/0.034, mask ~0.008. No possible GC/
safepoint overlap: CPU work in storage, not stopped/descheduled FX/grid/mask.
Profiled samples only, not historical-cause proof or complete scheduling accounting.

Every overview: same-thread outside-TLAB events identify three `FractalData`
arrays, 19,302,240 bytes at 1512x982/77,208,816 at 3024x1964 including headers/
alignment; no trap plane. Remaining constructor work scalar: allocation/initialization
is identified path. Completion events do not split allocator/clearing/faults/
instrumentation or time individual allocations. Missing outside-TLAB mask events
can mean TLAB allocation, not no allocation.

GC explains other outliers: 3024x1964 Refined ES2 wall 1.487 ms, CPU 0.615,
overlap 0.830. Metal CPU-heavy cases show another mechanism. No independent-median
subtraction/GC-corrected verdict.

Localized profiled path: `FractalData` arrays. Justifies designing bounded
cancellable worker-storage experiment, not production promotion. Exact allocator/
zeroing/fault/historical failure cause stays `inconclusive`. No tracing-based speedup,
series pooling/outcome-driven extension. Both failures/pending screen/other gates
unchanged. Raw JFR/config/patch/launches/hashes/analysis private; sanitized summary only.

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

12 JVMs exit: each 144 requests/80 measured, total 960. IDs/sample/ARGB match;
all final same-grid controls pass. Dedicated analyzer validates all groups/30
matched observations/six pairs, no profiling/missing samples/fallback pipelines.

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

95% upper targets <=0.90, controls <=1.05. Only fresh target inconclusive:
3024x1964 overview Refined. Table includes every overview FX target/first-visible
control. Descriptive median ms; matched median B/A, not pooled-median ratios.
Predeclared 5,000 seeded hierarchical resamples. All 84 groups remain in private
analysis/decision.

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

Observed per-pair RSS cap <=1.05 from whole-child `wait4`, including setup/cold/
warm/render/controls, not live bytes/leaks. Show every pair, lower worker peaks
and both violations:

| Pair / order | FX peak MiB | Worker peak MiB | B/A | Cap verdict |
| --- | ---: | ---: | ---: | --- |
| 0 / AB | 1990.8 | 1533.6 | 0.7703 | pass |
| 1 / BA | 1865.5 | 1780.6 | 0.9545 | pass |
| 2 / AB | 1813.6 | 1731.8 | 0.9549 | pass |
| 3 / BA | 1837.2 | 1932.7 | 1.0520 | fail |
| 4 / AB | 1818.1 | 1991.8 | 1.0956 | fail |
| 5 / BA | 1829.2 | 1907.8 | 1.0429 | pass |

Pairs 3/4 peak RSS increases 5.2%/9.6% fail cap. GC/commit/native mechanism unassigned,
no persistent-leak claim. Valid run stays valid, never environment-reclassified/
rerun for preferred ratios. Keep all pairs/groups.

Separate correctness: eight deterministic lifecycle tests, 12 FX on Metal/same
12 ES2, all-fixture 96x66 FAST/REFINED worker smoke. Cover actual allocation
drain, latest coalescing while blocked, delayed-ready replacement, pre-publish
close/errors/FX responsiveness/exact fresh-reuse pixels. The small smoke does not establish realistic-size latency; the paired campaign
provides that evidence within its declared scope. Benchmark does not accept
app generations/resize/scenes/cache-AA/platforms.

Keep FX default. Worker stays explicit test-source experiment for replay/regressions,
not production/accepted optimization. Lower FX in most groups fails joint latency/
memory; no relaxed limits/appended pairs. Future candidates need new budget/
correctness/gesture latency/memory. P1.7 failures/pending screen/other gates unchanged.
Raw logs/CSV/launch-resource/frozen inputs/full analysis private; summary/policy public.

Post-review tooling, no new timings: analyzer validates full fixture×size×step/
action against manifest. Tests delete same fixture-size across all JVMs and a
pan step, requiring rejection. Original replay confirms 84 groups/2,520 matched
metrics/six RSS pairs; statistics/policy/source/`reject` unchanged.

Public [preparation runner](../scripts/benchmarks/run_frame_preparation.py) now
writes before-after builds/launch-resource/completion. Synthetic children test
full orchestration against real analyzer; headless preflight exercised preserved
byte-identical binaries. Historical private runner was different; no new public-
runner measured campaign. Protocol has fresh/preflight commands; raw archive private.

## Limits

- One Mac model; results do not transfer to Intel Macs or other GPUs, and
  Windows and Linux do not use Metal.
- Screen observation is a JavaFX Robot capture proxy, not physical scanout.
- The Space-switching defect that motivated the comparison was checked
  manually, not by this benchmark.
