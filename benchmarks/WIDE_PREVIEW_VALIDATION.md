# Wide preview candidate

## Contract and predeclared experiment

The candidate adds a display-only reserve beneath the existing exact images.
`WidePreviewSettings.CANDIDATE` requests twice the visible width and height with
one sample per eight **physical** pixels, no AA. At 1512x982 this is 379x247.
At 3024x1964, step eight would give 757x492, which exceeds the 262,144-sample
limit, so the step rises to 10 and the reserve is 606x394. The iteration cap is 4096, the
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

`WidePreviewSettings.DEFAULT` is disabled after the experiment below. Explicit
configuration (`CANDIDATE`, or the validated five-argument constructor) enables
developer evaluation; there is no JVM switch or Settings UI. Setting DEFAULT
again stops work and clears the raster without changing the exact frame.

The configuration setter cancels pending publication and work, clears old
display pixels, and schedules replacement after visible work, without changing
the exact frame. This is the integration boundary for a future Settings UI.

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
(`CANDIDATE` by default, or `DEFAULT`) after source conformance and before the
identical 250 ms think time. It records the value and whether the preview API
exists in `environment.txt`, and stops if `DEFAULT` leaves any reserve snapshot. The
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
