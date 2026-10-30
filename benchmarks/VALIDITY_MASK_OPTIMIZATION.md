# Bounded validity scans and cancellable CPU planning

Script layout (2026-10-15): historical commands/measurement identities are preserved. For new runs use `scripts/benchmarks/`; see [the scripts index](../scripts/README.md).

> Historical evidence: results and “current source” describe the dated experiment, not validation of this checkout. Only [selected evidence](README.md) is included; “private archive” entries are unavailable here. Original SHAs/commands may require private revisions or inputs. See [provenance and replay limits](../docs/development/PUBLICATION.md).

**P0.3 final, 2026-09-21: reject.** Retained-frame gain repeats, but headless/
FX 30-fresh-pair controls stay inconclusive. Bounded mask/direct planner removed.
Julia still needs cancellable `missingRowSpans` on original `BitSet`;
overflow-safe region validation stays as independent correctness fix.

Candidate source: `9778ac9` on `codex/validity-mask-bounded-scans`.
Baseline source: `1a33e6f` (the same production code calibrated in
[the paired protocol validation](BASELINE_PAIRS_VALIDATION.md)).

## Problem and implementation

Unbounded `BitSet.nextClearBit(from)` scans ready suffix per tile row, penalizing
complete/nearly-complete frames. Calibration 3024x1964: ~6.5 s backend despite
5,939,136 ready pixels/zero new regions. Separate scheduling attributes mask;
coordinator also lacked preparation cancellation checks.

Candidate `ValidityMask`: same flat bitmap in 64-bit words/exact ready count,
row-bounded masked searches/no per-row bitmap. Overlap counts newly set bits only;
constant-time count/complete. Preserve locked clear/copy/shift/detached `BitSet`
snapshot semantics.

Direct planner skips full frames, checks tile/row cancel-interrupt, discards
abandoned plan before workers. Perturbation selection/scanning also cancellable.
Ordinary queries retain exact snapshots on interrupted threads; only explicit
overload aborts.

Numerical iteration, precision selection, tile order, worker count, palette,
AA sample policy and CPU/GPU dispatch rules are unchanged.

## Regression coverage

- A deterministic pixel/BitSet oracle checks random overlapping writes, counts,
  complete/clear/copy state, region-local snapshots and exact missing spans at
  widths 1, 31, 63, 64, 65, 127, 129 and 3024.
- The reported Retina case is tested with a single missing final pixel. It
  scans every 32-pixel tile, so a complete-frame shortcut alone cannot pass the
  regression. A generous two-second budget guards against repeated suffix scans.
- Cancellation partway through a mask scan discards the partial plan without
  modifying readiness; interruption preserves ordinary query semantics.
- A direct-planner regression checks that cancellation occurs before any
  progress or additional ready pixels can be published.
- A latch-controlled production render-service test cancels while the
  coordinator is inside mask scanning. Its diagnostic completion waits for
  coordinator exit, registers zero workers and emits no stale callbacks.
- Existing exact direct/perturbation rendering, retained-pixel publication,
  shifted-mask, coloring, AA and input-handler tests remain applicable.

## Predeclared measurement design

The [headless policy](policies/9-3-validity-headless-candidate.json)
uses 3024x1964 and preserves all 11 target/control metrics from calibration.
The [FX policy](policies/9-3-validity-fx-candidate.json) uses
1512x982 with FAST and REFINED requested modes. Fully reused reverse full
publication is the FX target; first publication is a control because ready
pixels may be displayed before backend completion.

Each campaign uses six fresh process pairs in AB/BA order, five measured samples
per side in each process (30 matched samples per metric), three warmups and one
cold sequence. Six process clusters replace calibration's three, with equally
represented launch orders. Target/control limits remain 0.90/1.05 for B/A.
Build identities, dependencies, benchmark bytecode, selected inputs and runtime
are checked by the [paired launcher](BASELINE_PAIRS_BENCHMARK.md). Campaigns,
tests and diagnostic memory work run sequentially.

The candidate binary identity is
`c8dff651c71ca7ef1220df412cdd03f7834063ce2f4c3aeb96db27854331334f`;
baseline identity is
`03204a7d9dbcbbf4754756c0e4746ce171404d328e05499435602bdc43352a75`.
Both were built with the same OpenJDK 26.0.2 and retained as standalone snapshots.

The machine is an Apple M3 Pro (Mac15,7), 12 logical CPUs, 18 GiB RAM,
macOS 26.6.2, with a 4 GiB Java heap. All recorded before/after thermal probes
were `nominal`; continuous thermal sampling was reserved for the separate soak.
Headless process peak RSS ranged from 2.39–3.17 GB for A and 2.48–3.29 GB for B;
FX ranged from 1.70–2.02 GB for A and 1.25–2.66 GB for B. These include startup,
controls and the full process workload, not just retained reverse navigation.

Headless observations all used battery power. The last FX baseline process (`pair-05-A`,
BA order) started on battery and ended on AC power; the other recorded FX endpoints were
on battery. This is an environmental confound for that pair. It is retained in the
predeclared analysis rather than selectively removed. The next control campaign must
hold power source fixed. The large target effect is observed in this dataset; the
campaign does not prove an unconfounded general performance or production-promotion
decision.

## Headless results

Raw campaign and analysis (private archive: `baseline-9-3-validity-headless-20260909/analysis.json`)
passed strict pair, workload and non-cancelled sample/ARGB fingerprint validation.
Every metric has 30 matched samples across six process pairs.

| Scope at 3024x1964 | Baseline median ms | Candidate median ms | Paired B/A interval | Timing gate |
| --- | ---: | ---: | ---: | --- |
| Retained reverse: backend | 6517.836 | 0.038 | below 0.00001 | Pass |
| Retained reverse: returned ARGB | 6569.526 | 45.163 | 0.00657–0.00829 | Pass |
| Direct frame: returned ARGB | 3024.872 | 2991.046 | 0.985–1.023 | Pass |
| Deep frame: returned ARGB | 8057.577 | 8040.514 | 0.987–1.023 | Pass |
| Julia AA: full operation | 1059.557 | 1033.499 | 0.945–1.001 | Pass |
| Pan source: returned ARGB | 218.809 | 227.388 | 0.938–1.044 | Pass |
| Overview: returned ARGB | 77.499 | 80.196 | 0.991–1.156 | Inconclusive |
| Overview: first new region | 7.158 | 12.973 | 0.901–1.457 | Inconclusive |
| Julia AA: first AA tile | 14.693 | 14.351 | 0.772–1.061 | Inconclusive |
| Pan: returned ARGB | 50.458 | 49.884 | 0.789–1.160 | Inconclusive |
| Cancellation after first region: tail | 0.527 | 0.536 | 0.504–1.981 | Inconclusive |

The returned-ARGB medians improve by about 145x on the complete retained frame.
All 5,939,136 pixels remain reused, with zero new regions. This confirms removal
of the reported backend wait; it is not a claim of a 145x faster numerical
iteration kernel or physical display.

Six pass/five inconclusive, none definite fail. Overview/short tails cannot prove
5% controls; never drop/assume equivalence. Aggregate `inconclusive` despite target
gain. Linked analysis retains process/paired/p95; separate-median ratio is not paired.

## JavaFX publication results

Raw FX campaign and analysis (private archive:
`baseline-9-3-validity-fx-20260909/analysis.json`) passed all 12 JVM exact-control runs
and the strict paired validator. Each 18 metric groups has 30 matching observations
across six process pairs.

| Scope at 1512x982, requested mode | Baseline median ms | Candidate median ms | Paired B/A interval | Timing gate |
| --- | ---: | ---: | ---: | --- |
| Retained reverse full publication, FAST | 432.256 | 19.542 | 0.0448–0.0457 | Pass |
| Retained reverse full publication, REFINED | 431.502 | 19.837 | 0.0454–0.0535 | Pass |
| Retained reverse first publication, FAST | 11.437 | 11.405 | 0.984–1.007 | Pass |
| Retained reverse first publication, REFINED | 11.379 | 11.681 | 0.989–1.187 | Inconclusive |
| Direct full publication, FAST | 730.055 | 724.217 | 0.968–1.027 | Pass |
| Direct full publication, REFINED | 735.228 | 729.354 | 0.977–1.046 | Pass |
| Deep full publication, FAST | 1942.655 | 1952.221 | 0.982–1.033 | Pass |
| Deep full publication, REFINED | 1965.806 | 1971.874 | 0.940–1.056 | Inconclusive |
| Julia AA full publication, FAST | 293.750 | 277.015 | 0.922–1.049 | Pass |
| Julia AA full publication, REFINED | 279.620 | 272.659 | 0.917–1.056 | Inconclusive |

Complete retained-frame publication improves by about 22x. Early publication
already displayed reused pixels before the old backend scan ended; its median
remains around 11 ms. This distinction prevents presenting a completion gain
as the same gain in first-visible latency. Base-only and deep fixtures use the
production effective FAST path even when REFINED is requested; Julia AA tests
the actual refined presentation path.

FX seven pass/eleven inconclusive, including both Julia first and six pan/source.
Pan-source REFINED 1.088 [1.041–1.150] cannot rule out >5%. Aggregate
`inconclusive`, no definite failures; larger predeclared campaign, no relaxed limits.

## Test execution

The final `mvn clean test` run reports **400 tests, no failures/errors, 37 skipped**.
The isolated graphical run reports **40 tests, no failures/errors, two skipped**;
enabled parameterized tests expand differently from skipped classes, so these counts
must not be added as a unique-test total. All **39 Python baseline-validator tests**
pass. Production class hashes after the clean build match the measured candidate
snapshot.

The test logs and production-class hash check (private archive: `baseline-9-3-validity-headless-20260909/test-evidence`)
are retained with the results. The two graphical skips are optional native
fullscreen cases requiring the separate `fractal.fx.fullscreen` setting.

Reused-JVM graphical run failed two initializers after earlier `Platform.exit()`
prevented restart. Separate fresh processes resolve test lifecycle without production edits:

```shell
mvn -Dfractal.fx.tests=true -DreuseForks=false \
  -Djavafx.cachedir=/tmp/fractallens-javafx-cache \
  -Dtest=FractalResizeFxTest,BaselineFxBenchmarkTest,BaselineInputBenchmarkTest test
```

The earlier input-only run with scheduling diagnostics enabled also passed all
16 tests. The numerical/concurrency suite and real FX campaigns therefore test
both normal and diagnostic execution paths.

## Rejected diagnostic memory run

Private `baseline-9-3-validity-memory-20260909`: 108 exact trials **not accepted**,
wall scope 107.868 ms shorter than monotonic. Independent GC wall/uptime shift:

- `11:42:34.549-0600`, uptime `573.146s`;
- `11:42:38.090-0600`, uptime `576.794s`.

Wall advanced 3.541 s/uptime 3.648: ~107 ms backward adjustment within log
resolution, independent clock-step evidence, not renderer pause. Cause uninvestigated.
Private originals retained; strict 10 ms limit unchanged. Bidirectional step
regressions/39 Python tests pass. Fresh soak needed no driver/production change.

## Accepted sustained-memory validation

The fresh final memory run (private archive: `baseline-9-3-validity-memory-final-20260909/summary.json`)
passes the unchanged strict validator: **108 exact trials, three complete
rounds, 802.084 seconds (13.37 minutes)**. It uses all eight default memory
fixtures at 1512x982, FAST/REFINED, one cycle per fixture per round, a 600-second
minimum, the same production classes as candidate `9778ac9`, JavaFX 26.0.2 at
2x output scale, and a 4 GiB heap. Scheduling diagnostics and NMT are enabled;
JFR is disabled. This process ran separately from A/B measurements and tests.

| Observation | Result |
| --- | ---: |
| Detached heap, rounds 1 / 2 / 3 | 126.830 / 126.842 / 126.846 MB |
| Detached heap, first-to-last change | +16,112 bytes |
| Non-heap, rounds 1 / 2 / 3 | 41.242 / 43.114 / 44.074 MB |
| Process RSS peak / final observation | 2.786 / 1.272 GB |
| Recorded thermal states | nominal only |
| GC time: navigation / exact controls / seeds and controls | 3.306 / 3.582 / 3.213 s |

Actual manifests/exact frames/drains/clocks/rounds/native captures validate.
Detached heap flat; prior baseline ~126.93–126.95 MB, observational not paired
allocation/RSS gains. Native/nonheap warms; three checkpoints cannot prove
absence of every leak. Thermal is OS pressure, not temperature; controls/forced GC excluded
from speed claims.

Reproduction after compiling the test drivers and dependency classpath:

```shell
PYTHONDONTWRITEBYTECODE=1 python3 scripts/run_baseline_memory.py NEW_OUTPUT \
  --java /opt/homebrew/opt/openjdk/bin/java --seconds 600 --cycles 1 \
  --sizes 1512x982 --modes FAST,REFINED --thermal /tmp/fractallens-baseline-thermal
PYTHONDONTWRITEBYTECODE=1 python3 scripts/summarize_baseline_memory.py NEW_OUTPUT
```

The production source revision and full source hashes are recorded per run.
Memory driver, launcher and validator snapshots remain in the private campaign
archive; the only additional script edit is the clock-step rejection test.

## Scope limits

Pixels/FX publication are not physical gesture latency. Actual reverse manifests
own decimal rounding, not canonical exact cache-hit assumptions. First-region cancel
is not planning cancel; polling may miss shortened window, so deterministic tests
cover in-preparation cancellation.

Hashes never replace exact conformance; target gain alone neither passes controls
nor promotes. Interpret recorded workload/hardware only: macOS evidence, Windows/
compositor/scanout separate.

## P0.3 final paired decision — 2026-09-21

The [headless policy](policies/9-3-validity-headless-p0-3.json) and
[JavaFX policy](policies/9-3-validity-fx-p0-3.json) preserved the
original fixtures, 0.90 target limit and 1.05 control limit. Each scheduled
30 alternating AB/BA process pairs, one measured sequence and three warmups per
side, yielding 30 matched observations for every declared metric group. The
same frozen `1a33e6f` control and `9778ac9` candidate were rebuilt with
OpenJDK 26.0.2. Their compiled identities match the earlier campaign exactly:
`03204a7d9dbcbbf4754756c0e4746ce171404d328e05499435602bdc43352a75`
and `c8dff651c71ca7ef1220df412cdd03f7834063ce2f4c3aeb96db27854331334f`.
The three candidate production classes are unchanged between `9778ac9` and the
pre-decision `main` revision `506ac1f`. Later Julia changes are outside these
frozen builds, so the paired timing claims apply to the declared revisions and
workloads, while current-tree correctness needs separate tests.

Exact preparation and campaign commands (each output path was new):

```shell
python3 scripts/prepare_baseline_build.py 1a33e6f /tmp/fractallens-p0-3-build-a --java /opt/homebrew/opt/openjdk/bin/java
python3 scripts/prepare_baseline_build.py 9778ac9 /tmp/fractallens-p0-3-build-b --java /opt/homebrew/opt/openjdk/bin/java
SWIFT_MODULE_CACHE_PATH=/tmp/fractallens-p0-3-swift-cache CLANG_MODULE_CACHE_PATH=/tmp/fractallens-p0-3-swift-cache swiftc scripts/baseline_thermal.swift -o /tmp/fractallens-p0-3-thermal
PYTHONDONTWRITEBYTECODE=1 python3 scripts/run_baseline_pairs.py benchmarks/policies/9-3-validity-headless-p0-3.json benchmarks/baseline-p0-3-validity-headless-20260921 --a /tmp/fractallens-p0-3-build-a --b /tmp/fractallens-p0-3-build-b --java /opt/homebrew/opt/openjdk/bin/java --thermal /tmp/fractallens-p0-3-thermal
PYTHONDONTWRITEBYTECODE=1 python3 scripts/summarize_baseline_pairs.py benchmarks/baseline-p0-3-validity-headless-20260921
python3 scripts/run_baseline_pairs.py benchmarks/policies/9-3-validity-fx-p0-3.json benchmarks/baseline-p0-3-validity-fx-desktop-20260921 --a /tmp/fractallens-p0-3-build-a --b /tmp/fractallens-p0-3-build-b --java /opt/homebrew/opt/openjdk/bin/java --thermal /tmp/fractallens-p0-3-thermal
PYTHONDONTWRITEBYTECODE=1 python3 scripts/summarize_baseline_pairs.py benchmarks/baseline-p0-3-validity-fx-desktop-20260921
```

The compiled snapshots remain locally available at the `/tmp` paths above;
their identities and complete file hashes are retained in both campaigns'
`build-A.json` and `build-B.json`. Repeating the commands requires fresh output
paths. The JavaFX command needs an active macOS graphical desktop session.

Both campaigns: M3 Pro (Mac15,7), 12 logical CPUs/18 GiB, macOS 27.0/4 GiB
heap. Headless sandbox; FX desktop ES2 all 60 JVMs. **Each** has 120 AC brackets/
120 `nominal` thermal readings, not continuous temperature/no-transient proof.
No build/test/profiler competition. JVM wall total headless 4064.1 s/FX 2254.0 s.
RSS GiB: headless A 2.078–2.992/B 2.011–2.932; FX A 1.504–1.961/B 1.485–1.991.
Whole-process peaks, not per-case improvement. Headless A/B GC 366/370,
1000/1257 ms (240 rows/side); FX 152/163, 338/355 ms (360). Median heap
907.5/905.7 MiB headless, 780.1/682.6 FX. Descriptive, not allocation/leaks.
Historical 13.37-minute candidate soak not repeated on rejected production state.

The [headless analysis](baseline-p0-3-validity-headless-20260921/analysis.json)
passed strict launch, workload and matching sample/ARGB fingerprint validation.
Seven of 11 metric groups passed; four controls were inconclusive. Values below
are milliseconds, with descriptive p95 in parentheses and the bootstrap 95%
interval for the matched B/A ratio:

| Scope | A median (p95) | B median (p95) | B/A interval | Gate |
| --- | ---: | ---: | ---: | --- |
| Retained reverse, returned ARGB | 4979.390 (5095.334) | 35.935 (60.211) | 0.00700–0.00755 | Pass |
| Overview, returned ARGB | 49.905 (53.380) | 52.390 (57.185) | 1.001–1.098 | Inconclusive |
| Overview, first region | 4.930 (9.015) | 7.164 (10.155) | 0.804–1.690 | Inconclusive |
| Julia AA, first tile | 11.513 (20.169) | 11.941 (32.134) | 0.868–1.062 | Inconclusive |
| Cancellation tail | 0.381 (1.789) | 0.411 (2.833) | 0.683–1.709 | Inconclusive |

Retained backend median 4943.218 →0.036 ms. Direct/deep/Julia operations/both
pan-completion controls pass; linked analysis holds all metrics/p95/process/intervals.
Static `confidence_method` still says three calibration clusters; policy/launches/
`process_pairs` and actual bootstrap use **30** process IDs.

The [JavaFX analysis](baseline-p0-3-validity-fx-desktop-20260921/analysis.json)
passed all 60 exact-control JVM runs, strict paired validation and the same
fingerprint check. Thirteen of 18 metric groups passed; five controls were
inconclusive:

| Scope | A median (p95) | B median (p95) | B/A interval | Gate |
| --- | ---: | ---: | ---: | --- |
| Retained reverse full publication, FAST | 327.929 (337.369) | 15.708 (21.601) | 0.04730–0.04982 | Pass |
| Retained reverse full publication, REFINED | 327.221 (333.795) | 15.717 (21.331) | 0.04744–0.04898 | Pass |
| Julia AA, FAST first publication | 8.842 (15.847) | 7.795 (15.349) | 0.644–1.211 | Inconclusive |
| Pan, FAST first publication | 8.964 (9.991) | 9.317 (13.251) | 0.993–1.093 | Inconclusive |
| Pan, FAST full publication | 14.432 (16.877) | 15.036 (19.916) | 0.996–1.124 | Inconclusive |
| Pan, REFINED first publication | 8.691 (9.906) | 9.160 (10.422) | 1.004–1.054 | Inconclusive |
| Pan, REFINED full publication | 14.005 (16.313) | 14.508 (16.524) | 1.004–1.058 | Inconclusive |

Private sandbox failure `baseline-p0-3-validity-fx-sandbox-failure-20260921/pair-00-A.log`:
Prism lacks main screen/first JVM stopped, no sample. Desktop reran fresh output,
unchanged policy/builds/metrics.

**Reject `9778ac9`.** Both 30-pair aggregates `inconclusive`; no dropping or
passing unresolved controls. Budget exhausted; real retained gain does not prove
5% controls. Remove candidate, restoring long full-reuse planning. Redesign needs
new complete predeclared correctness/timing; physical gesture/display still unmeasured.

### Final-tree verification

Reverted tree: targeted mask/planner/diagnostics/Julia then `mvn clean test`,
427/no failures-errors/48 skips. Desktop `FractalResizeFxTest`,
`BaselineFxBenchmarkTest`, `BaselineInputBenchmarkTest`: 43 passes/two gated
fullscreen skips; all 39 Python validators pass. Stale viewport assertion also
failed pristine `506ac1f`; [correction](BASELINE_INPUT_VALIDATION.md) checks
`10^-25` pixel grid with exact scale/size/cap/independent pixels. Canonical data/
historical timings unchanged. No new 30-pair reverted-tree campaign: earlier
frozen control measured; restored implementation implies known planning wait.
