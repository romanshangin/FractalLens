# Bounded validity scans and cancellable CPU planning

Candidate source: `9778ac9` on `codex/validity-mask-bounded-scans`.
Baseline source: `1a33e6f` (the same production code calibrated in
[the paired protocol validation](BASELINE_PAIRS_VALIDATION.md)).

## Problem and implementation

`BitSet.nextClearBit(from)` has no upper bound. When a tile row was already
ready, its search could scan the entire ready suffix of the image. Calling it
again for every tile row made complete or almost-complete frames particularly
expensive. The previous 3024x1964 calibration measured roughly 6.5 seconds in
the backend for a retained frame with all 5,939,136 pixels ready and no newly
calculated regions. The separate scheduling probe attributed that cost to mask
scanning. The coordinator also failed to check cancellation during preparation.

`ValidityMask` now stores the same flat bitmap in 64-bit words with an exact
ready-pixel count. Word searches stop at the requested row interval and mask
both ends, without allocating a temporary bitmap per row. Marking an overlapping
region increments the count only for newly set bits. Completeness and count
queries are constant time. Clear, copy, shifted copy and detached `BitSet`
snapshot semantics are retained under the existing mask lock.

The direct CPU planner returns immediately for complete frames, checks
cancellation/interruption between tiles and within row scanning, and discards
an abandoned plan before worker submission. Perturbation's tile-selection loop
also checks cancellation, and its missing-span scan uses the cancellable path.
Ordinary mask queries still return their exact snapshot even on an interrupted
thread; only the explicitly cancellable overload aborts scanning.

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

The [headless policy](benchmarks/policies/9-3-validity-headless-candidate.json)
uses 3024x1964 and preserves all 11 target/control metrics from calibration.
The [FX policy](benchmarks/policies/9-3-validity-fx-candidate.json) uses
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

Headless observations all used battery power. The last FX baseline process
(`pair-05-A`, BA order) started on battery and ended on AC power; the other
recorded FX endpoints were on battery. This is an environmental confound for
that pair. It is retained in the predeclared analysis rather than selectively
removed. The next control campaign must hold power source fixed. The large
target effect is observed in this dataset; the campaign does not establish an
unconfounded general performance or production-promotion decision.

## Headless results

[Raw campaign and analysis](benchmarks/baseline-9-3-validity-headless-20260909/analysis.json)
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

Six metric groups pass and five are inconclusive; none has a definite timing
failure under the declared interval rule. In particular, the overview and short
latency intervals do not establish the 5% control budget. They must not be
silently accepted as equivalent or removed from the gate. The aggregate
headless verdict is therefore `inconclusive`, despite the decisive target gain.
Per-process ratios, paired point estimates and descriptive p95 values remain
in the linked analysis; the ratio of separate medians is not the paired estimate.

## JavaFX publication results

[Raw FX campaign and analysis](benchmarks/baseline-9-3-validity-fx-20260909/analysis.json)
passed all 12 JVM exact-control runs and the strict paired validator. Each of
the 18 metric groups has 30 matching observations across six process pairs.

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

Seven FX groups pass and eleven are inconclusive. In addition to the rows above,
both Julia first-publication groups and all six pan/source groups remain
inconclusive. Pan-source REFINED has a paired estimate of 1.088 and interval
1.041–1.150, so a regression beyond the 5% budget cannot be ruled out. The
aggregate FX verdict is `inconclusive`; no groups are definite failures under
the predeclared interval rule. Retain the unresolved controls for a separate
larger, predeclared measurement rather than relaxing their acceptance limits.

## Test execution

The final `mvn clean test` run reports **400 tests, zero failures/errors, 37
skipped**. The isolated graphical run reports **40 tests, zero failures/errors,
two skipped**; enabled parameterized tests expand differently from skipped
classes, so these counts must not be added as a unique-test total. All **39
Python baseline-validator tests** pass. Production class hashes after the clean
build match the measured candidate snapshot.

The [test logs and production-class hash check](benchmarks/baseline-9-3-validity-headless-20260909/test-evidence)
are retained with the results. The two graphical skips are optional native
fullscreen cases requiring the separate `fractal.fx.fullscreen` setting.

Running all graphical classes in one reused JVM initially failed two class
initializers: an earlier suite calls `Platform.exit()`, which prevents a later
suite from restarting JavaFX. Running each graphical class in a fresh process
resolves that test-lifecycle conflict without changing production code:

```shell
mvn -Dfractal.fx.tests=true -DreuseForks=false \
  -Djavafx.cachedir=/tmp/fractalui-javafx-cache \
  -Dtest=FractalResizeFxTest,BaselineFxBenchmarkTest,BaselineInputBenchmarkTest test
```

The earlier input-only run with scheduling diagnostics enabled also passed all
16 tests. The numerical/concurrency suite and real FX campaigns therefore test
both normal and diagnostic execution paths.

## Rejected diagnostic memory run

The first [memory run](benchmarks/baseline-9-3-validity-memory-20260909)
completed 108 exact trials but is **not accepted**: one scope's wall duration
was 107.868 ms shorter than its monotonic duration. The independent JVM GC log
also shows a persistent wall/uptime offset change across that scope:

- `11:42:34.549-0600`, uptime `573.146s`;
- `11:42:38.090-0600`, uptime `576.794s`.

Wall time advanced 3.541 seconds while uptime advanced 3.648 seconds, confirming
an approximately 107 ms backward wall-clock adjustment within the resolution of
that log. This is independent evidence of a clock step, not an inferred renderer
pause. Its cause (for example time synchronization) was not investigated.
The original CSVs and logs are preserved. The existing strict 10 ms consistency
limit is unchanged; a new regression checks rejection of both clock-step
directions. The Python validator suite now passes **39 tests**. No benchmark
driver or production change was needed to repeat the soak in a fresh directory.

## Accepted sustained-memory validation

The fresh [final memory run](benchmarks/baseline-9-3-validity-memory-final-20260909/summary.json)
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

All actual-input manifests, frame controls, cancelled-request drains, scope
clocks, whole-round coverage and native-memory captures pass validation.
Detached heap remains flat at this scale; the previous baseline's comparable
checkpoints were about 126.93–126.95 MB. This comparison is observational,
not a paired allocation or RSS-improvement claim. Native/non-heap warmup is
visible, and three detached checkpoints cannot prove absence of every leak.
Thermal state is OS pressure rather than physical temperature. Controls and
forced GC belong to this diagnostic process and are excluded from speed claims.

Reproduction after compiling the test drivers and dependency classpath:

```shell
PYTHONDONTWRITEBYTECODE=1 python3 scripts/run_baseline_memory.py NEW_OUTPUT \
  --java /opt/homebrew/opt/openjdk/bin/java --seconds 600 --cycles 1 \
  --sizes 1512x982 --modes FAST,REFINED --thermal /tmp/fractalui-baseline-thermal
PYTHONDONTWRITEBYTECODE=1 python3 scripts/summarize_baseline_memory.py NEW_OUTPUT
```

The production source revision and full source hashes are recorded per run.
Memory driver, launcher and validator snapshots are retained in each memory
directory; the only additional script edit is the clock-step rejection test.

## Scope limits

Headless returned pixels and JavaFX publication are distinct from physical
gesture-to-display latency. The input driver's decimal reverse gesture may
miss the canonical exact retained-frame match; its own actual-input manifest
is authoritative. Cancellation after the first completed region is also
distinct from planning cancellation. The old polling-based complete-frame
planning-cancellation probe can miss the newly shortened planning window;
deterministic regressions cover cancellation inside preparation instead.

Fingerprints do not replace exact numerical conformance. A positive target
result alone does not establish the complete control budget or promote the
candidate. Timing, memory and input results must be interpreted within their
recorded workloads and hardware. These measurements are macOS evidence;
Windows and physical compositor/scanout measurements remain separate work.
