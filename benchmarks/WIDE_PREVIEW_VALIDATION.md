# Wide preview candidate

> Historical policy: the campaigns below measured the former 262,144-sample
> default and terminal two-second deadline. The current application uses a
> 256 MiB preview-storage planning estimate and continues idle work across
> cooperative quanta. These historical results do not establish performance,
> memory or thermal acceptance for that policy. The fresh
> [QB2 comparison](#byte-budget-comparison-qb2-with-sleep-inhibition-2026-11-0910)
> retained the default byte-budget policy on its declared Apple Silicon matrix,
> including active-desktop checks and verified sleep-inhibitor cleanup. QB1
> remains inconclusive and environment-limited. See
> [the current UI contract](../docs/platform/MACOS_UI.md#wide-navigation-preview).


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

## Quality byte-budget implementation diagnostic (2026-11-08)

This is new implementation evidence, separate from the historical promotion
campaigns above. Code state: `fix/preview-quality-render-time`, uncommitted
production changes on base `3ac9e1dd5dfe8a50cdbaba19a2e600d54795f060`.
SHA-256 of `git diff HEAD -- src/main` followed by the bytes of the new
`src/main/java/com/shangin/fractal/render/WidePreviewMemory.java`:
`a962323c6e87de78a895475eaa0b88347c09207cd8f589b3876d2584e9ea2be4`.

A private, temporary in-process diagnostic exercised the production
`WidePreviewService` with synchronous callbacks, one warm-up and three measured
requests per configuration. Inputs: Mandelbrot, center
`(-0.743643887037151, 0.13182590420533)`, scale `0.024`, 3024×1964 physical
pixels, 1000 iterations, extent 2×, Ice coloring. Every completed color array
matched a full single-worker `DirectDoubleRenderBackend` control for that exact
planned grid; checksums repeated across all three requests per configuration.
The legacy-grid row uses an explicit 262,144-sample ceiling in the current
service; it is not a frozen historical executable or a paired speedup control.

Environment: macOS 27.2, aarch64, JDK `26.0.1+8-34`, 12 available processors,
one preview worker. Power/thermal state and competing desktop workloads were
uncontrolled. Raw output and the temporary driver were not selected for public
publication. Times exclude JavaFX upload/scanout; MiB values are planning
estimates, not measured heap/RSS.

| Policy / requested step | Effective step | Samples | Estimated preview storage (MiB) | First publication (ms), range | Completion (ms), range |
| --- | ---: | ---: | ---: | ---: | ---: |
| Legacy grid / 8 px | 10 px | 238,764 | 20.70 | 1.13–1.97 | 46.03–47.44 |
| Byte budget / 8 px | 8 px | 372,444 | 29.75 | 0.93–1.34 | 71.74–72.76 |
| Byte budget / 4 px | 4 px | 1,487,279 | 104.34 | 2.80–3.25 | 299.21–300.85 |
| Byte budget / 2 px | 3 px | 2,642,270 | 181.16 | 3.21–5.23 | 542.54–551.09 |

The checked-in `WidePreviewCancellationBenchmark` also completed its one warm-up
and three measurements for Mandelbrot/Julia at scales `0.024` and `1e-80`,
3024×1964, 3000 iterations, current default policy. Warm cancellation-to-worker-
drain ranges were 0.294–0.898 ms (Mandelbrot standard), 0.080–0.084 ms
(Mandelbrot deep), 0.171–0.180 ms (Julia standard), and 0.040–0.046 ms (Julia
deep). It cancels after the first completed region, so this does not establish
late-frame cancellation, interaction latency or native presentation timing.

Decision: **retain the requested functional policy for review**. Quality now
changes the production grid below the byte budget, and an over-budget request
is explicitly reported. These diagnostics do not establish a performance
promotion or transfer TG2-4 acceptance: production gesture/AA pairs, retained
heap/RSS/GC, sustained thermal behavior and other platforms remain unverified.
Continuation across elapsed work quanta, slow-region progress, cancellation,
recolor reuse, no-progress failure and stale-publication suppression are covered
by the implementation regression tests rather than these short timing runs.


### Follow-up: constant-time cancellation polling

The timing diagnostics above preceded this implementation follow-up and were
not rerun for it. A regression on a 2017×1310 mask, filled except for its last
row, reproduced 100,000 cardinality scans during 100,000 cancellation polls in
a slow continuation after the quantum deadline. The predicate now reads an
atomic progress flag set by `regionCompleted` and reset for each quantum.
The final regression requires 100,000 cancellation polls to finish on another
thread while the test holds the mask monitor, both before and after the first
completed region. Since `readyPixelCount` is synchronized, it cannot be used in
that polling path. The test also checks continuation to a complete snapshot. The once-per-quantum no-progress guard still counts ready samples;
publication counts are unchanged. These are polling-path regressions,
not a new measurement of full-render speed, RSS or thermal behavior.


## Byte-budget comparison QB1 (2026-11-09)

**Decision: inconclusive, with an additional graphical-environment limitation;
performance acceptance remains open.** This was
implementation self-validation of the requested functional policy, not an
independent verification stage. No production sources were changed during or
after the campaign. Earlier TG2 decisions and the implementation diagnostic
above retain their original scope.

### Frozen inputs and declaration

Control A was `main` at
`3ac9e1dd5dfe8a50cdbaba19a2e600d54795f060`, with the enabled `DEFAULT`
preview, the 262,144-sample cap and terminal two-second deadline. Candidate B
was the uncommitted `fix/preview-quality-render-time` tree on that same base,
including the constant-time cancellation-polling correction, with enabled
`CANDIDATE`, the 256 MiB planning estimate and continuing idle work quanta.
Both used production default extent 2x and requested step 8 px.

Frozen compiled snapshot identities:

- A: `65ebb65d5ca40f485bf0a5099e96400daa06b648e2189e23f61fd74e94ddbdfd`.
- B: `0a82b0c4bc2534d52039dd6b729d1278e24c4b668a78007175ca30636f531531`.

Both builds used the pinned Maven Wrapper. The candidate snapshot explicitly
included the new, untracked `WidePreviewMemory.java` without changing the real
Git index. The benchmark driver source and ordered dependencies matched.
Source and compiled manifests remain private; hashes were verified after the
campaign, and current source bytes still matched the candidate source manifest.
A dirty candidate cannot be reconstructed from the base revision alone.

The policy, controller, chain and build manifests were hashed before measured
processes. SHA-256 of the private declaration-hash record:
`8b295580b7ea9d783ccfdb512783673af34f35834ed85e37e2724446971a23f7`.
An excluded smoke run first passed exact controls on both builds. No smoke or
historical measurements were pooled into QB1.

Environment: macOS 27.2, aarch64, Apple M3 Pro, 12 physical cores, 18 GiB RAM,
JDK `26.0.1+8-34`, JavaFX 26.0.2, Metal Prism, CPU rendering, `-Xmx4g`, no
initial-heap or GC overrides. All recorded process boundaries reported AC power
and nominal thermal pressure. The chain ran under `caffeinate -i` without
competing builds/tests or requested manual window input. This assertion prevented
system idle sleep, but did not establish an unlocked, continuously active display.
The post-campaign checks below exposed that limitation.

### Fixed protocol and calibration

The matrix was the existing `WidePreviewInputBenchmark`: pan-25 and
wide-zoom-out, 1512x982 and 3024x1964, FAST and REFINED. Each process ran one
first sequence, three warmups and five measured sequences per cell, with the
same 250 ms source think time. Each process passed the existing exact CPU
sample/ARGB controls and complete-matrix validator; matched processes had
identical final fingerprints. All frozen-build checks passed.

A fresh 12-pair A/A stage ran the frozen control with `DEFAULT` under both
labels. A/A timing overall was 0.995 (0.975-1.017), and memory v2 G1/G2 both
passed. The declared joint rule selected the smallest N in {12,18,24,30,36}
with timing joint pass >= 0.80, memory pass >= 0.80 and each false-fail rate
<= 0.05. At N=12 the simulated rates were 0.924 for timing and 0.982 for memory,
with zero false fails for either. The observed A/A gates did not fail.

The selected plan was hashed before candidate measurement, then exactly 12
fresh A/B pairs ran in alternating AB/BA order. SHA-256 of the private joint
plan: `3812b758cac1922af604de6828e0c41d50afb017be4401b4e9f1a3208a21a81a`.
There was no early stop, omitted pair or retry. The limits were unchanged:
[timing v2](TIMING_GATE_V2.md), [memory v2](MEMORY_GATE_V2.md), preview
availability >= 95%, exact controls and nominal process-boundary thermal state.

### Results

| Component | Result |
| --- | --- |
| Exact controls, complete matrices and frozen builds | pass in all 24 A/A and 24 A/B processes |
| AC power / thermal pressure | AC / nominal in all 96 boundary observations |
| Candidate reserve ready / complete before measured gestures | 100% / 100% |
| Candidate-stage peak RSS range, A / B | 3.933-4.505 GB / 4.020-4.512 GB |
| Memory v2 G1 paired median ratio | 1.005 (0.997-1.017), pass |
| Memory v2 G2 P90 ratio | 0.989 (0.981-1.006), pass |
| Timing overall B/A, 32 primary groups | 1.003 (0.973-1.042), pass |
| Timing family averages | 7 pass, 1 inconclusive, 0 fail |
| Individual timing groups | 56 pass, 0 inconclusive, 0 fail |

The unresolved family is pan first-input-to-preview after layout, across both
sizes and modes: B/A 1.043962, 95% interval 0.976799-1.131088. Its upper bound
exceeds 1.13, so it does not pass. The measured average increase is about 4.4%;
the interval remains too wide to establish the accepted 13% limit. The largest
primary group estimate is 3024x1964 REFINED pan preview after layout, 1.067
(0.945-1.205), within the individual-group rule. No statistical component confirms a failure.
The overall and seven other families passing cannot waive the unresolved one.

### Graphical-environment limitation discovered after measurement

A fresh final JavaFX run on unchanged application sources completed 91 tests
with zero assertion failures, two errors and two fullscreen skips. The errors
were timeouts in `MacModalWindowsFxTest.closeConfirmationKeyboardReturnsSafely`
(the Enter case) and `settingsSelectStandardAndDeepZoomModesFromPopUpButtons`.
Both wait for native keyboard dismissal. The run also emitted
`CVDisplayLinkCreateWithActiveCGDisplays` and related errors (`-6661`). An explicit
desktop-access check then reported that the Mac was locked and required manual
unlock. This supports an environment blocker for keyboard-based UI validation;
it does not establish a new application defect. Before the campaign, the same
91-test JavaFX selection had passed with only the two fullscreen skips.

After the owner manually unlocked the Mac, the same two methods were rerun on
unchanged application/test sources in the active desktop session, with a fresh
JavaFX native cache, `-DreuseForks=false` and a temporary `caffeinate -di`
assertion. All three cases passed (Escape and Enter dismissal, plus settings
pop-up selection), with zero failures, errors or skips and no CVDisplayLink
diagnostics. This resolves the observed keyboard-test environment blocker.
It does not alter QB1's historical session conditions or timing verdict.

Retrospective inspection found the same four CVDisplayLink diagnostics in 22 of
24 A/A process logs and 22 of 24 A/B process logs. Neither smoke process nor the
first two A/A processes recorded them. The exact lock/unlock chronology was not
recorded, so continuously active desktop conditions cannot be asserted for QB1.
All processes still initialized Metal Prism, passed exact final-output checks
and returned successfully. The numerical timing/memory verdicts above preserve
what the analyzer computed; they do not validate the affected graphical-session
conditions. Any confirmatory timing campaign must establish an active desktop
throughout; removing these observations or treating the run as an active-display
PASS is not permitted.

No performance promotion follows this result. No production tuning, limit
relaxation or follow-up retry was performed after seeing it. Pre-existing
user-owned functional changes remain in the task branch; they are not newly
accepted as an optimization. Closing timing acceptance requires a separately
declared follow-up and experiment budget; QB1 must remain inconclusive.

### Evidence and scope limits

The raw process records, logs, policies, hashes, plans, commands, manifests,
source/binary snapshots and analysis stay in the private QB1 archive. This
selected summary is not a public replay bundle. Measurements cover standard
Mandelbrot at default preview settings on this Apple Silicon Mac. Non-default
quality/extent choices, long sessions, retained-heap/GC attribution, physical
scanout, sustained temperature/energy, deep Julia, native fullscreen,
Windows/Linux and Intel/AMD Macs remain unverified by this campaign.
Process-boundary thermal pressure and peak RSS do not establish those properties
or turn the 256 MiB planning estimate into a process-memory cap. Manual tooltip
inspection was not performed in this implementation continuation.


## Byte-budget comparison QB2 with sleep inhibition (2026-11-09/10)

**Decision: retain for the declared default-settings matrix.** QB2 is a fresh,
owner-requested implementation self-validation campaign after QB1's graphical
session limitation was diagnosed. No production tuning or gate relaxation was
made. QB1 remains inconclusive and environment-limited; none of its measurements
or calibration plan was pooled into QB2. This is not independent verification.

### Inputs and fixed protocol

The frozen A/B snapshots are the same verified binaries described in QB1:
A `65ebb65d5ca40f485bf0a5099e96400daa06b648e2189e23f61fd74e94ddbdfd`,
B `0a82b0c4bc2534d52039dd6b729d1278e24c4b668a78007175ca30636f531531`.
A uses the enabled legacy `DEFAULT`; B uses the enabled byte-budget `CANDIDATE`.
Both have extent 2x and requested step 8 px. The application/test source bytes
still match B's source manifest. Compiled snapshots, ordered dependencies,
driver source, declaration and plan hashes were verified after measurement.
No source, helper, policy or analysis edits occurred during the campaign.

Environment and workload are those recorded for QB1: macOS 27.2, aarch64,
Apple M3 Pro, 12 physical cores, 18 GiB RAM, JDK `26.0.1+8-34`, JavaFX 26.0.2,
Metal Prism, CPU rendering and `-Xmx4g`. The existing wide-input matrix covers
pan-25 and wide-zoom-out, 1512x982 and 3024x1964, FAST and REFINED. Each process
runs one first sequence, three warmups and five measured sequences per cell,
with identical 250 ms source think time. Exact final sample/ARGB controls,
complete-matrix validation and the timing v2/memory v2 limits are unchanged.

The policy, controller, supervisor, session probe, frozen manifests and analysis
sources were hashed before measured processes. SHA-256 of the private
declaration-hash record:
`34fe12055e74517cd2888d8868e5fa96661b9a9eefd3275aff49fdf685869f49`.
A fresh 12-pair A/A stage used the frozen control with `DEFAULT` for both labels.
Its timing overall was 0.992 (0.961-1.022), and memory G1/G2 both passed.
The unchanged joint selection rule chose N=12: simulated timing joint pass
0.970, memory pass 0.861 and zero false fails for either. The joint plan was
hashed before the first candidate process:
`7e6d824df5054717fe7aa660484b9fa2a3af833d40c501ebcfd2d302e9a73bca`.
Exactly 12 fresh A/B pairs then ran, alternating AB/BA, with no early stop,
omitted pair or retry.

### Active session and inhibitor lifecycle

A supervisor wrapped the entire foreground chain, including calibration and
final analysis, in `caffeinate -di`. It confirmed both of this run's owned
system-idle and display-idle assertions before measurement. Native CoreGraphics
queries checked that the session was present, on console, logged in, not
reported locked, and had an active awake display. Those conditions, AC power
and both assertions were sampled every five seconds and before/after every JVM.
Missing protection or session state, a reported lock, a sleeping display or any
CVDisplayLink diagnostic was a hard stop. Checks use the observed macOS session
dictionary, including its lock field when present; they are not a guarantee
about every OS version or every sub-five-second transition.

All **2,509 campaign guard samples passed**. All 48 JVM logs were free of
CVDisplayLink diagnostics. All 96 process-boundary observations reported AC power
and nominal thermal pressure. Excluded preflights had also checked normal and
intentional-cancellation cleanup before declaration; they are not measurements.

After the full chain completed successfully, the supervisor verified that its
caffeinate helper was gone, both owned assertions had been released, and the
persistent power configuration matched its pre-run snapshot. Cleanup passed,
with no remaining helper or assertion. No persistent sleep/display setting or
security policy was changed, and no other process's inhibitors were targeted.
The analyzer's retain verdict was accepted only after this cleanup check passed.

### Results

| Component | Result |
| --- | --- |
| Exact controls, complete matrices, fingerprints and frozen-build checks | pass in all 24 A/A and 24 A/B processes |
| Active session, awake display, AC and owned inhibition | all 2,509 guard samples pass |
| Candidate reserve ready / complete before measured gestures | 100% / 100% |
| Candidate-stage peak RSS range, A / B | 3.884-4.417 GB / 4.038-4.361 GB |
| Memory v2 G1 paired median ratio | 1.007 (0.981-1.033), pass |
| Memory v2 G2 P90 ratio | 0.991 (0.985-1.002), pass |
| Timing overall B/A, 32 primary groups | 0.984 (0.956-1.011), pass |
| Timing family averages | 8 pass, 0 inconclusive, 0 fail |
| Individual timing groups | 56 pass, 0 inconclusive, 0 fail |
| Owned inhibitor cleanup / persistent power configuration | released / unchanged |

The pan-preview-after-layout family that was inconclusive in QB1 is 0.991
(0.914-1.072), passing the unchanged 1.13 upper-bound limit. All family upper
bounds are between 0.999 and 1.072. The largest individual group estimate is
3024x1964 FAST pan preview before layout, a diagnostic metric, at 1.235 against
a 0.397 ms control median. It passes the existing 1 ms floor rule; no group or
average limit was changed after seeing the data.

The overall interval includes 1, so this is acceptance within the declared
regression limits, not proof of an overall speedup. Memory G1/G2 pass their
unchanged 1.05 upper-bound limits. No per-pair RSS rule is substituted for those
predeclared gates. The 256 MiB estimate remains a planning model, not a measured
heap/RSS cap.

### Decision scope and retained evidence

Retain the requested byte-budget/continued-work policy at the measured production
defaults on this Apple Silicon matrix. This closes its default-matrix performance
acceptance gap without relabeling QB1 or TG2-4. Numerical/lifecycle tests continue
to cover quality changes, continuation across work quanta and cancellation;
this short standard-precision matrix does not establish sustained deep-zoom
behavior. Non-default extent/quality settings, long sessions, retained-heap/GC
attribution, physical scanout, energy/temperature, deep Julia, native fullscreen,
Windows/Linux and Intel/AMD Macs remain outside this campaign's acceptance.
Manual tooltip appearance was not inspected during this continuation.

Raw logs, process records, session samples, policies, hashes, plans, commands,
native probes and cleanup records stay in the private QB2 archive. The reused
frozen binaries/manifests remain in their original private archive. This selected
summary is not a public replay bundle. Nothing was committed or uploaded by the
campaign.
