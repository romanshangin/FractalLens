# Wide preview candidate

## Contract and predeclared experiment

The candidate adds a display-only reserve beneath the existing exact images.
`WidePreviewSettings.CANDIDATE` requests twice the visible width and height with
one sample per eight **physical** pixels, no AA. At 1512x982 this is 379x247.
At 3024x1964, step eight would give 757x492, which exceeds the 262,144-sample
limit, so the step rises to 10 and the reserve is 606x394. The measured candidate
capped iterations at 4096 (removed later, see
[the deep-zoom correction](#correction-deep-zoom-iteration-cap)), the
maximum decimal working precision is 512 digits, and unsupported coarse jobs
produce no reserve. Preview samples never enter visible validity, exact caches,
AA candidate selection, completion status, or export.

Foreground rendering and AA have priority: reserve preparation starts only
after COMPLETE. New navigation cancels it cooperatively without waiting on FX.
The next reserve task waits for the preceding backend and its workers to return.
Only one coordinator task and the latest pending request are retained. A single
worker calculates the reserve. Each request has a two-second cooperative wall
budget, including reference construction; it may leave a partial reserve.
Publication is coalesced to at most one queued FX operation, normally at 50 ms
intervals and at completion. It transfers immutable rasters with transparent
unknown cells. No approximate samples are treated as exact pixels.

Ready samples are kept in one private coarse frame. Same-spacing pans snap the
reserve center to that coarse lattice and copy only compatible calculated
samples through the existing reuse planner. New scale, formula, Julia constant,
orbit trap, iteration limit or configuration cannot reuse incompatible samples.
The previously displayed reserve remains projected during navigation until the
successor has something to show, including an adaptive cap change caused by
zoom. Explicit iteration-policy changes invalidate that display as well.
Color changes remove the previous colored
reserve synchronously and recolor compatible samples in the background.
Histogram preview uses the existing smooth progressive palette; exact final
histogram coloring is unchanged. All three display modes allow this background
approximation. Antialiased display still publishes only AA target tiles above it.

`WidePreviewSettings.DEFAULT` was disabled after the experiment below and has been
`CANDIDATE` since [TG2-4](#follow-up-campaigns-tg2-3-aborted-and-tg2-4-retain). Explicit
configuration (`CANDIDATE`, or the validated four-argument constructor) enables
the preview; there is no JVM switch. Setting `DISABLED` stops work and clears the
raster without changing the exact frame.
A later Settings switch (Navigation → Wide preview, an application preference)
selects exactly `CANDIDATE` or `DISABLED`; it adds no render-path code, and the
benchmark driver constructs `FractalView` directly, so no saved preference reaches
a measured process.

The configuration setter cancels pending publication and work, clears old
display pixels, and schedules replacement after visible work, without changing
the exact frame. This is the integration boundary the Settings switch uses.

### Frozen comparison policy (declared before timing)

Use the same JDK, dependencies, Prism pipeline and unmodified
`WidePreviewInputBenchmark` driver in both snapshots. Control is main
`09dd3dc`; candidate is the uncommitted working-tree snapshot, with source and
binary hashes retained privately by `prepare_baseline_build.py --working-tree`.
The only added source in the control is the identical benchmark driver, which
uses stable pre-existing APIs and reflection only for untimed reserve inspection.

- Six alternating AB/BA process pairs, five measured samples, three warmups,
  and one first sequence per case (the existing six-pair CPU protocol budget).
- Physical sizes: 1512x982 and 3024x1964. Cases: existing pan-25 and a centered
  1.5x zoom out from its exact source. FAST and REFINED modes.
- Identical 250 ms source think time after exact conformance in both builds.
  Reserve availability is recorded before the gesture; that wait is outside
  gesture timing. Initial reserve latency is assessed separately.
- Target: a ready reserve before the gesture in at least 95% of measured
  sequences, rather than a claim of faster exact calculation. Sparse quality
  is checked against exact calculations and JavaFX projection/coverage tests.
- Controls: first input to transformed preview; last input to first target,
  first AA, and COMPLETE, plus next post-layout observations where present.
  Apply the existing seeded 5000-resample hierarchical paired bootstrap.
  Each declared group must have an upper 95% B/A bound <= 1.05.
- Peak RSS must be <= 1.05 of control in every process pair. This is a bounded
  process-memory comparison, not a leak or long-session ownership proof.
- Separate cancellation diagnostic runs use the existing production input
  benchmark, cancel-direct/cancel-deep at 480x270, one warmup and three samples,
  with render diagnostics enabled. Retain actual worker-drain tails separately
  from profiler-free timing.
- Every measured final sample and ARGB frame must pass the existing same-grid
  CPU conformance control. Cancelled generations must not promote a frame.
- Observe power/thermal pressure before/after each process; temperatures,
  scanout, hardware input and energy are unmeasured. Do not run other tests,
  builds or measurements concurrently with the timing campaign.

Decision is `retain` only if correctness, availability, timing and RSS controls
pass. A failed control is `reject`; an unresolved interval or unavailable
required measurement is `inconclusive`. Such a candidate is not promoted.

## Execution and results

An initial control process was stopped after a developer-discovered correctness
issue: the first candidate cleared ready display pixels when zoom changed an
adaptive iteration cap. No A/B pair completed and no performance decision was
drawn from it. A regression now distinguishes adaptive navigation from an
explicit iteration-policy edit. The fresh campaign uses the corrected candidate
and the same predeclared cases/counts/limits. Its JVMs also include the normal
JavaFX native bridge exports; the aborted control used the JavaFX loading-material
fallback because those exports were absent. The two campaigns are not combined.

### Decision: reject (memory gate), 2026-11-04

The final active-policy campaign stopped when the hard RSS criterion failed.
Two AB/BA process pairs completed, each with 72 exact-controlled sequences per
process (40 measured sequences). A third control process was intentionally
terminated once the already completed pair's RSS result was assessed. It and the
earlier incomplete campaigns contribute no timing or memory samples to the
[selected summary](WIDE_PREVIEW_SUMMARY.csv). No CAMPAIGN_COMPLETE is claimed.

| Completed process pair | Control peak RSS, bytes | Candidate peak RSS, bytes | B/A |
| --- | ---: | ---: | ---: |
| AB | 3728834560 | 3716218880 | 0.996617 |
| BA | 3471654912 | 3849781248 | **1.108918** |

The second pair exceeds the declared 1.05 limit. This is a failed peak-process
memory criterion, **not proof of a leak or of 378 MB of live preview storage**.
Heap/GC/Metal allocation attribution and a new predeclared experiment are needed
before choosing a remedy. Reducing density without that diagnosis is not a
confirmed remedy. No further parameter tuning was attempted after the hard
rejection. The implementation is kept explicitly configurable but disabled by
default; it is not promoted as a production optimization.

The reserve was fully ready before input in **80/80 measured candidate
sequences**. Final samples and ARGB pixels passed the existing exact CPU controls
in every completed process. Each timing group has only **10 measured pairs
instead of 30**. The CSV's timing medians/paired ratios are descriptive only;
latency, first AA and completion controls remain **inconclusive**, with no
bootstrap acceptance or speedup claim. Physical input/scanout is unmeasured.

Environment: Apple M3 Pro, 12 logical CPUs, 18 GiB RAM, macOS 27.2 arm64,
Oracle JDK 26.0.1, JavaFX 26.0.2, Metal Prism, fixed 4 GiB heap, CPU calculation,
GPU dispatch off. Completed pair boundaries reported AC power and nominal
thermal pressure before and after each JVM. Temperatures and thermal state
throughout the processes were not measured.

Frozen control identity:
`e4450ac3a6178b88269a351f70bb4a6c180153e8e4bb6304d208695e381ccacd`.
Frozen active candidate identity:
`725862e027b11bd4f103ad17c1a7da9a207861a4e1824443ac90e5dcd8746641`.
Both derive from main `09dd3dc`; these identities describe the measured active
policy, before the final default-disabled configuration and its regression.
The final enabled/disabled policy boundary is covered by developer tests, not
by a new successful paired performance campaign.

### Cancellation and visible quality

Separate production-input cancellation diagnostics passed exact sample/ARGB
controls for both builds: cancel-direct/cancel-deep, 480x270, FAST/REFINED, one
warmup, three measured samples and one first sequence per case. Base worker drain
medians were 0.212/0.232 ms (control/candidate direct) and 0.0141/0.0144 ms (deep).
These diagnostic counts do not establish a cancellation-tail percentile bound
or AA cancellation latency.

`WidePreviewCancellationBenchmark` then cancelled after a real completed coarse
region. One warmup and three samples for each Mandelbrot/Julia x direct/1e-80
case at 3024x1964, cap 3000, gave measured reserve drain tails of **0.0415 to
0.5919 ms**. The off-FX coordinator fence includes backend return, worker drain
and backend close; it is not merely a cancelled Future state. Direct cases kept
1024 samples and precise cases 32. First regions took 0.91 to 24.85 ms in the
measured samples. Cancellation during reference construction remains unmeasured.

A live 2x-output-scale JavaFX inspection used a seahorse view in Antialiased
display: controlled pan and 1.5x zoom-out projections, step 8 versus 16, and an
actual wheel zoom-out through FractalView. Both filled exposed areas with real
coarse detail; step 8 was visibly less blocky. The live input converged to a
complete AA frame. This is a qualitative local inspection, not a multi-scene
quality metric. The standalone inspection also exercised CPU operation after an
optional LWJGL library-load warning; GPU availability was not a correctness gate.

### Earlier interrupted observation

The preceding active candidate's second process stopped with
`Last input lacks generation-bound completion` in a Retina FAST zoom-out
warmup. Its failing epoch was not saved by that driver version, so its product,
observer or environment cause remains unclassified. A separate corrected-driver
probe passed 12 sequences on the candidate and 48 measured sequences plus one
warmup/first sequence on main. The two complete pairs reported above did not
repeat it. It is **not claimed fixed**. The new driver preserves failed epochs
for diagnosis; it never retries a failed trial into PASS.

### Reproduction and evidence limits

Freeze both working-tree snapshots with `prepare_baseline_build.py
--working-tree`, the same pinned Maven Wrapper and JDK. New test sources must be
included in the tracked snapshot input (intent-to-add is sufficient); the control
gets only the identical `WidePreviewInputBenchmark` source. Preserve and verify
all source/class/dependency hashes before and after a campaign.

The test-module entry point is
`com.shangin.fractal/com.shangin.fractal.render.WidePreviewInputBenchmark` with
`baseline.sizes=1512x982,3024x1964`, `baseline.fx.modes=FAST,REFINED`,
`baseline.warmups=3`, `baseline.runs=5`, fresh `baseline.output` and
`javafx.cachedir`, `-Xmx4g`, `-Dprism.order=mtl`, CPU/GPU-dispatch flags as above,
and the normal native bridge module exports from pom.xml. The current driver
applies the `WidePreviewSettings` constant named by `wide.preview.settings`
(`CANDIDATE` by default, `DEFAULT` or `DISABLED`) after source conformance and
before the identical 250 ms think time. It records the value and whether the preview
API exists in `environment.txt`, and stops if a switched-off constant leaves any
reserve snapshot. Since TG2-4 `DEFAULT` equals `CANDIDATE`, so an off control on a
later build must use `DISABLED`. The
recorded campaigns used the earlier always-CANDIDATE driver, which matches
`CANDIDATE` here. This is a **future evaluation protocol**, not a relabeling of
the recorded default-active builds. Alternate six fresh JVM pairs AB/BA without
competing tests/builds, retain per-process RSS/exit/power/thermal records, and
validate every input run with `summarize_baseline_input.py`. Apply the previously
declared group/ratio limits, never only pooled medians.

The cancellation entry points are the existing `BaselineInputBenchmark` with
`baseline.fixtures=cancel-direct,cancel-deep`, `baseline.sizes=480x270`,
`baseline.warmups=1`, `baseline.runs=3`, `fractal.render.diagnostics=true`, and the
new headless `WidePreviewCancellationBenchmark`. For the latter, use one frozen
test module with the same JVM and no competing work.

Raw commands/controllers, logs, events, manifests, process records and frozen
binaries are archived privately, not included in this checkout. The selected CSV
contains only reviewed numerical summaries; it does not replay the campaigns.
Windows/Linux, native GPU, multi-monitor scale transitions, long-session retained
memory attribution, reference-construction cancellation, full deep-zoom visible
quality and the optional native-fullscreen scenarios remain unverified.

## Review corrections after the recorded experiment

The active-policy measurements above remain historical evidence for their
frozen builds. The following corrections do not relabel them as a new PASS:

- A complete private reserve bypasses backend construction and calculation;
  recoloring publishes it once. Incomplete work retains job-scoped backends so
  transient orbit state is closed after worker drain. The reference cache budget
  bounds that job's storage; it is not a cross-request orbit-reuse claim.
- Color Cycling suspends hidden reserve work/publication while visible recolors
  continue. Stopping resumes using the last applied visible palette. Navigation
  still cancels superseded work. Same-size publications reuse a WritableImage
  and a CPU upload array; snapshots stay immutable and private.
- A new regression first failed on the old code with two backend constructions
  instead of one for a repeated complete deep request. The fixed path asserts
  one calculation total and one publication for each requested palette.
- JavaFX regressions drive 30 real visible recolor ticks through FractalView,
  fencing the visible and reserve executors. They assert unchanged reserve
  generation/no hidden raster during cycling, changed visible pixels, and
  exact last-palette reserve pixels after stopping. Image/upload-array identity
  and pixel contents are checked across same-size recolors and dimension changes.

A local live JavaFX inspection also exercised start/stop cycling and then a
zoom-out preview projection in the corrected implementation. The visible image
animated and the resumed coarse layer matched its stopped palette. This was a
functional visual check, not a palette-performance measurement.

These are operation-count, allocation-identity and correctness regressions,
not profiler-free latency/RSS or palette-throughput measurements. Performance
acceptance of the updated candidate is **inconclusive**: no new paired campaign
has been run. The previous memory rejection stands and DEFAULT stays disabled.

A later [AA memory investigation](AA_MEMORY_VALIDATION.md) corrected the separate
AA cache graph budget. Its sample-allocation experiment reduced allocated bytes
but was rejected on process RSS and removed from production. It does not change
this historical wide-preview rejection or enable DEFAULT.


The distance-only follow-up in the AA report also failed its unchanged hard RSS
control after nine process pairs, despite passing allocation and exact-output
controls. It was removed as well. No new wide-preview input campaign was run;
this preview's original rejection and default-disabled policy remain in force.

## Follow-up: calibrated memory gate v2

The v1 per-pair RSS rule was later shown to measure G1 heap-expansion timing
rather than build differences. A separately declared, A/A-calibrated gate then
measured the default-disabled build `56730450...` (128 MiB AA cache default),
with the driver enabling CANDIDATE, against the same control on 6 fresh pairs. Memory **passed**: median RSS ratio
0.969 (0.925–1.031) and P90 0.992 (0.974–0.995). Exact controls passed, and
the reserve was ready in 100% of measured sequences. Timing had 31 pass,
25 inconclusive and 0 fail groups, including wide intervals for early 3024x1964
zoom-out publication. The decision is **inconclusive**, and DEFAULT stays
disabled. The v1 reject above remains the historical result for its builds. See
[memory gate v2](MEMORY_GATE_V2.md).

## Follow-up: timing extension TE1

TE1 was declared on 2026-11-05 before any of its processes ran. It used the same
control and candidate builds, external driver, command lines, matrix, per-group
statistic and 1.05 limit as the v2 wide stage. It ran **24 fresh process pairs**,
analysed alone, with no pooling with earlier pairs and no early stop. The
decision rule was unchanged: any fail is a reject, and retain needs every timing
group, memory v2, availability, exact controls and thermal state to pass.

TE1 focused on 12 early zoom-out publication groups: both sizes and modes ×
first input to preview after layout, last input to first target, and first
target after layout. A planning simulation, run before TE1 from the earlier
six-pair data, shows that this rule is underpowered for millisecond-scale
latency:

- With identical builds (A/A calibration clusters) at 24 pairs, the probability
  that all 56 groups pass was 0.000. Identical builds averaged only 18.6 passing
  groups.
- With identical builds, the probability that any of the 56 groups falsely
  fails was 0.092. For the focus family it was 0.000.
- If the six-pair candidate effect were real, the probability of a focus fail
  was 0.842.

| Control | Result |
| --- | --- |
| Exact sample/ARGB, complete matrices | pass for all 48 processes |
| Reserve ready / complete before gesture | 100% / 100% of measured sequences |
| Peak RSS, control range | 3.608–4.246 GB |
| Peak RSS, candidate range | 3.509–4.244 GB |
| Memory v2 G1 median ratio | 0.968 (0.943–0.980), pass |
| Memory v2 G2 P90 ratio | 0.999 (0.921–1.0502), inconclusive |
| Timing groups | 43 pass, 13 inconclusive, 0 fail (of 56) |
| Focus groups | 4 pass, 8 inconclusive, 0 fail (of 12) |
| Thermal pressure at process boundaries | nominal |

**The six-pair concern did not reproduce.** For 3024x1964 FAST zoom-out, first
input to preview after layout was 0.999 (0.840–1.112). The six-pair estimate had
been 1.427. Last input to first target was 1.033 (0.875–1.269). 3024x1964
REFINED first target passed at 0.898 (0.871–0.922).

The descriptive attribution for 3024x1964 FAST (pinch finished to render
submit) shrank to +1.1 ms median paired difference. The candidate was slower
in 15 of 24 pairs. At the other three size/mode combinations, the candidate was
equal or faster.

Completion latency improved or stayed level. 3024x1964 pan last input to
COMPLETE was 0.689 (0.643–0.759) in FAST and 0.778 (0.726–0.832) in REFINED.
All eight zoom-out and pan COMPLETE groups passed.

The 13 inconclusive groups were:

- five sub-millisecond first-input-to-preview groups, with medians of 0.26–0.42 ms;
- eight focus groups, with upper bounds of 1.07–1.27.

**Decision:** inconclusive. No regression is confirmed, but one is not excluded
either. Early zoom-out publication could still be up to about 27% slower at the
upper bound. Memory v2 was also inconclusive: its G2 tail bound exceeded 1.05
by 0.0002, so memory did not pass for these builds in TE1.
`WidePreviewSettings.DEFAULT` stays disabled. The planning simulation estimates
that identical builds pass all 56 groups with probability about 0 at 24 pairs
under the unchanged per-group rule. This is an estimate from six A/A pairs, not
a proof for every N, but adding pairs alone is unlikely to reach retain.
Promotion needs a timing acceptance rule redesigned and calibrated on A/A data,
which is an owner decision, and a memory result that passes both v2 gates. TE1 raw records, policy hashes and simulation scripts are
kept in the private archive.

The owner later set the limits of a replacement timing rule,
[timing gate v2](TIMING_GATE_V2.md): averages within 13% and individual groups
within 25%. It does not re-score TE1; TE1 stays inconclusive.

The v2 and TE1 measurements used the 128 MiB AA cache default. That default was
later [recalculated to 512 MiB](AA_MEMORY_VALIDATION.md#follow-up-default-budget-recalculation),
so these memory and timing results describe build
`567304500c12af3b7aa1f90308f2d43c6f3643e0a6eb6556a3c55dab338ffa8c`, not the
current tree. Any future promotion campaign must measure the current budget.

## Follow-up: timing gate v2 campaign TG2-1

TG2-1 was declared on 2026-11-05 before any of its processes ran. Its policy
and campaign script were hashed first. It applies [timing gate v2](TIMING_GATE_V2.md)
and memory gate v2 to the current tree, which has the 512 MiB AA cache default and
the Settings switch. A single frozen build of the working tree is its own control:
A uses `-Dwide.preview.settings=DEFAULT` (disabled) and B uses `CANDIDATE`. Command
lines equal the memory v2 wide stage: `-Xmx4g`, Metal Prism, CPU rendering,
1512x982 and 3024x1964, FAST and REFINED, 3 warmups and 5 samples, with AB/BA
alternation on AC power.

The declared stages were as follows:

1. A fresh 12-pair A/A stage (`DEFAULT`/`DEFAULT`).
2. A plan based only on that A/A data. Timing gate v2 picks the smallest N with
   joint pass ≥ 0.80 and false fail ≤ 0.05. At that N, a memory v2 simulation must
   reach pass ≥ 0.80 and false fail ≤ 0.05, and the observed A/A memory gate must
   not fail.
3. Exactly N candidate pairs, run only if both parts of the plan are runnable.

| A/A stage (12 pairs, 24 processes) | Result |
| --- | --- |
| Exact sample/ARGB fingerprints, frozen-build verification | pass in every pair |
| Thermal pressure at process boundaries | nominal (48 of 48 observations) |
| Peak RSS range | 3.794–4.210 GB |
| Observed timing v2 overall B/A | 0.999 (0.978–1.022), pass; 56 groups pass, 0 fail |
| Observed memory v2 G1 median ratio | 0.978 (0.967–1.003), pass |
| Observed memory v2 G2 P90 ratio | 0.970 (0.933–0.985), pass |

| Plan at N | Timing joint pass | Timing false fail | Memory v2 pass | Memory v2 false fail |
| --- | --- | --- | --- | --- |
| 12 | 0.751 | 0.000 | — | — |
| **18 (selected by timing)** | **0.915** | **0.000** | **0.671** | **0.000** |
| 24 | 0.979 | 0.000 | — | — |

**Decision: the plan is not runnable, so the candidate stage did not run.** The
timing gate became practical: the A/A stage passed every group, and 18 pairs give
0.915 power. Memory v2 has no false fails, but only a 0.671 pass rate at 18 pairs,
below the declared 0.80. With identical builds, about a third of 18-pair campaigns
would end inconclusive on memory alone. Under the declared policy the owner
decides how to continue. `WidePreviewSettings.DEFAULT` stays disabled, and the
Settings switch remains an explicit opt-in.

After the plan, a diagnostic was run on the same A/A RSS pairs. It is not part of
the declared plan, and its seeds differ from the plan's. It gives memory pass rates
of 0.684 at 18 pairs, 0.689 at 24, 0.777 at 30 and 0.829 at 36. The planning
requirement would first hold at about 36 pairs, which is roughly 5 hours of
candidate processes on this machine. Any continuation needs a new declaration
before its first process. One option chooses N from the joint timing and memory
power. The other changes the memory planning requirement, which is an owner
decision. TG2-1 raw records, plan files and the diagnostic stay in the private
archive.

Not covered: long sessions, GPU, Windows/Linux, Intel/AMD Macs and native
fullscreen.

## Follow-up: campaign TG2-2 (36 pairs)

The owner chose to continue with 36 pairs. TG2-2 was declared on 2026-11-06 before
any of its processes ran. Its policy, script and frozen plan files were hashed first.
It reuses the TG2-1 A/A stage for planning only. Its timing plan is the TG2-1
simulation with `selected` set to N = 36, which gives 0.999 joint pass and 0.000
false fail. At N = 36 the memory v2 simulation gives 0.829 pass and 0.000 false
fail. The build and command lines are the same as in TG2-1: the build's sources
equal the current tree, and A is `DEFAULT` while B is `CANDIDATE`. The decision
rule is unchanged. The campaign ran exactly 36 fresh pairs, with no pooling and no
early stop.

| Component | Result |
| --- | --- |
| Exact sample/ARGB fingerprints, frozen-build verification | pass in all 36 pairs |
| Thermal pressure at process boundaries | nominal (144 of 144 observations) |
| Reserve ready / complete before gesture | 100% / 100% of measured sequences |
| Peak RSS range, A / B | 3.768–4.382 GB / 3.662–4.355 GB |
| Memory v2 G1 median ratio | 0.993 (0.983–1.009), pass |
| Memory v2 G2 P90 ratio | 1.009 (0.957–1.045), pass |
| Timing v2 overall B/A | 0.997 (0.965–1.030), pass |
| Timing v2 families | 7 pass, 1 inconclusive, 0 fail (of 8) |
| Timing v2 groups | 55 pass, 1 inconclusive, 0 fail (of 56) |

The family and group that did not pass are both zoom-out first input to preview
after layout:

- The family ratio is 1.072 (1.009–1.135), and its upper bound exceeds the 1.13
  average limit.
- 3024x1964 REFINED is the only group above its limit: 1.381 (1.216–1.569)
  against a control median of 13.3 ms, about +5 ms.
- That group's Bonferroni-adjusted lower bound is 1.100, which does not exceed
  the 1.25 group limit, so the excess is not confirmed as a fail.
- 3024x1964 FAST in the same family passed at 1.094 (0.931–1.285).
- Both 1512x982 groups were faster: 0.905 (FAST) and 0.964 (REFINED).

Completion latency improved or stayed level. Zoom-out last input to AA was 0.954
(0.945–0.963), and zoom-out last input to post-layout was 0.999 (0.994–1.004).

**Decision: inconclusive.** Memory v2, availability, thermal state and exact
controls all pass, and no timing component fails. The point estimate for the
first Retina REFINED zoom-out preview is above the owner's 25% group limit, so
more pairs alone would most likely tighten the interval around a value that still
exceeds the limit. `WidePreviewSettings.DEFAULT` stays disabled, and the Settings
switch remains an explicit opt-in. Promotion needs an owner decision. One option
is a change that reduces the candidate's first-preview cost at that size and mode,
followed by a new declared campaign. TG2-2 raw records, the policy and its hashes
stay in the private archive.

Not covered: long sessions, GPU, Windows/Linux, Intel/AMD Macs and native
fullscreen.

## Correction: deep-zoom iteration cap

The reserve capped iterations at `min(visible limit, 4096)`. When a deep-zoom
frame needs more iterations than that, a reserve sample that has not escaped by
4096 is not proven interior. It was colored as interior, though, so the reserve
showed black where the exact frame was colored. Near
`-0.743643887037158704752191506114774 + 0.131825904205311970493132056385139i` at
scale `1e-16` with 20,000 iterations:

- The visible frame escapes at every sample.
- The capped reserve was 100% black.

The reserve now uses the visible frame's iteration limit. The two-second
cooperative budget, cancellation and transparent unreached cells still bound
and label the work. `WidePreviewSettings` no longer has an iteration-limit
component. `WidePreviewTest` checks that the plan keeps the visible limit and
that reserve colors equal a deep-reference render of the coarse job, and it
fails with the old cap.

Every fixture of the recorded campaigns has a visible limit below 4096, so
their measured work is unchanged. Deep scenes above the old cap were never part
of any timing or memory gate. They now spend more idle CPU time per reserve
sample, and the larger reference orbit and BLA table are allocated only while the
reserve is calculated.


## Diagnostic: first Retina zoom-out preview after TG2-2

This is a diagnostic, not a gate. It is not declared and does not change TG2-2.
TG2-2 events separate the excess in the 3024x1964 first zoom-out preview after
layout. Medians over 180 measured sequences per label:

| Gap (ms) | FAST A | FAST B | REFINED A | REFINED B |
| --- | --- | --- | --- | --- |
| render start → reuse planned | 9.7 | 13.2 | 8.1 | 14.1 |
| reuse planned → surface prepared | 0.9 | 1.1 | 0.5 | 0.7 |
| surface prepared → preview after layout | 3.2 | 2.9 | 3.1 | 3.0 |

At 1512x982 the planning gap is about 3.1 ms for both labels. The reserve does
no work at this step: it only cancels, which is a no-op when idle, and updates
one transform. The preview pulse waits for planning on the FX thread.

Instrumented diagnostic builds of the same sources measured
`wide-zoom-out` at 3024x1964 in 4 + 4 processes per build:

- **GC.** No collection completed during planning in any of 160 sequences.
- **Pre-touched heap.** With `-Xms4g -XX:+AlwaysPreTouch` (3 + 3 processes)
  the planning times stayed spread out (2–30 ms) and B was not faster.
- **Thread CPU time.** In planning, user time was a stable 1.6–2.1 ms median.
  The variable part was kernel time: 1.4–9.1 ms median and up to 15.5 ms.
  Off-CPU time was below 0.1 ms. Zoom-out never reused samples, so planning
  allocated a fresh 3024x1964 frame of about 77 MB each time.

The cost is therefore page-fault and zero-fill time, spent while the fresh
visible frame is allocated on the FX thread. It exists without the reserve too,
up to 17 ms in A. The likely reason the reserve raises it in TG2-2 is the
process's larger memory footprint, under which more pages of each new frame are
not resident. These small diagnostic samples did not reproduce the A/B
difference itself (median REFINED 11.1 vs 11.5 ms). Cutting this cost means not
allocating a cold full-size frame on the FX thread for every render, for example
by recycling frame storage or allocating it off the FX thread. That is a
render-pipeline lifecycle change for both labels and needs its own correctness
checks and a new declared campaign. The instrumentation was not kept, and the
diagnostic builds, raw records and scripts stay in the private archive.

That change was later retained as
[spare frame storage](SPARE_FRAME_STORAGE_RESULTS.md): fresh-frame storage is
allocated ahead of time off the JavaFX thread.
[TG2-4](#follow-up-campaigns-tg2-3-aborted-and-tg2-4-retain) later re-measured
the reserve on a tree that includes it. TG2-2 stays inconclusive.

## Follow-up: campaigns TG2-3 (aborted) and TG2-4 (retain)

Both campaigns measured one frozen build of a tree that includes spare frame
storage and the visible-limit deep-zoom correction. Its production sources differ
from the tree that enables the default in three files, none on the measured path:

- the `WidePreviewSettings.DEFAULT` constant, which the driver bypasses by applying
  the named constant explicitly;
- `PreferencesStore`, whose later change saves a choice over an unreadable file; it
  is used only by `MainView`, which the driver does not create;
- one comment in `FractalRenderController`.

Labels, driver, command lines, fixture matrix and decision rule are
those of TG2-2. A is `DEFAULT` (then disabled) and B is `CANDIDATE`, and the A/A
stage uses `DEFAULT` for both labels. Each campaign was declared and hashed
before its first process. The whole chain ran under `caffeinate -i`, on AC
power, with no other build running.

Each declaration fixed a joint timing and memory plan: a fresh 12-pair A/A
stage, then the smallest N in {12, 18, 24, 30, 36} that meets all of:

- timing v2 joint pass ≥ 0.80 and false fail ≤ 0.05;
- memory v2 pass ≥ 0.80 and false fail ≤ 0.05, from a resampling simulation
  over the A/A pairs;
- the observed A/A timing and memory gates do not fail.

If no N qualified, the plan was not runnable and the owner would decide.
Exactly N candidate pairs then ran, with no early stop.

**TG2-3 (2026-11-06): aborted, no decision.** The A/A stage stopped at its
fifth pair:

- In a pinch zoom-out step, a mouse drag arrived 1.47 s after the harness pinch
  and caused no camera change. The harness synthesizes drags only for pan
  steps, so this was external pointer input to the on-screen benchmark window.
- The driver's generation-bound completion check stopped the process, which is
  a declared hard stop.
- This is environment interference, not a product defect.

No TG2-3 pair is used or pooled. The owner asked for a restart.

**TG2-4 (2026-11-07).** The A/A stage passed timing v2: overall 1.015
(0.991–1.041) and no failing group. It also passed memory v2. The plan selected
N = 12, where the simulations give:

- timing joint pass 0.861 with 0.000 false fail;
- memory pass 1.000 with 0.000 false fail.

The candidate stage then ran 12 fresh pairs.

| Component | Result |
| --- | --- |
| Processes, exact sample/ARGB fingerprints, frozen-build verification | pass in all 24 A/A and 24 candidate processes |
| Thermal pressure at process boundaries | nominal (96 of 96 observations) |
| Reserve ready / complete before gesture | 100% / 100% of measured sequences |
| Peak RSS range, A / B | 4.290–4.554 GB / 4.353–4.555 GB |
| Memory v2 G1 median ratio | 0.996 (0.986–1.008), pass |
| Memory v2 G2 P90 ratio | 0.990 (0.988–1.006), pass |
| Timing v2 overall B/A | 0.973 (0.946–0.997), pass |
| Timing v2 families | 8 pass of 8, upper bounds 0.983–1.046 |
| Timing v2 groups | 56 pass, 0 inconclusive, 0 fail |

The TG2-2 concern is gone. First zoom-out preview after layout was 0.920
(0.859–0.983) for the family. Its 3024x1964 REFINED group was 0.923
(0.824–1.034) against a 12.1 ms control median, where TG2-2 had 1.381. The other
three groups of that family were 0.910–0.934. The highest group ratio was
3024x1964 FAST zoom-out last input to first target, a diagnostic metric, at
1.080 (0.966–1.207), within its 1.25 limit.

**Decision: retain.** Timing v2, memory v2 G1 and G2, availability (100% ≥ 95%),
thermal state and exact controls all pass. `WidePreviewSettings.DEFAULT` is now
`CANDIDATE`. Users without an explicit choice get the preview. An explicit
Settings choice, saved as `widePreview=on|off`, still wins. The driver now also
accepts `DISABLED`, and checks that any switched-off constant leaves no reserve.
Its `DEFAULT` label no longer means "off" on builds after this change.
`timing_gate_v2.py` still labels the control `DEFAULT`, as recorded in
TG2-1–TG2-4. A later off/on campaign on a build with this default needs a
declared `DISABLED` control label.

Raw records, the policies and their hashes stay in the private archive.

Not covered: long sessions, GPU, Windows/Linux, Intel/AMD Macs, native fullscreen,
and deep-zoom scenes above 4096 iterations, where the reserve now spends more
idle CPU per sample.
