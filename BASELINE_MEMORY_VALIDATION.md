# Roadmap 9.1 allocation and sustained memory — 2026-09-08 local

The allocation profile identifies repeated arbitrary-precision coordinate work
in deep AA as a follow-up target. This step adds diagnostic infrastructure and
evidence; it does not optimize the production renderer or establish a speedup.
See [the workload and reproduction contract](BASELINE_MEMORY_BENCHMARK.md).

Branch: `codex/roadmap-9-1-memory-profiling`, based on `c2ec1a8`. Data identify
that base plus the working tree through source SHA-256 manifests. The final
sustained runner additionally moves each case into a completed helper method
before the detached GC checkpoint, avoiding dependence on the liveness of
large `Result` locals in the long-running main method. The earlier allocation
profile remains useful for allocation attribution; its detached-memory values
are not the final sustained ownership evidence.
The earlier driver and launcher are retained in
[source snapshots](benchmarks/baseline-9-1-memory-allocation-20260908/source-snapshots);
their SHA-256 values exactly match the allocation run's recorded manifest.

## Environment and boundaries

The selected JDK is Homebrew OpenJDK **26.0.2**, explicitly invoked from
`/opt/homebrew/opt/openjdk/bin/java`, with `-Xmx4g` and NMT summary enabled.
The platform is Apple M3 Pro, 12 reported processors, macOS 26.6.2. Launch logs
verify JavaFX 26.0.2, the ES2 pipeline, Apple M3 Pro renderer, vsync and 2x scale.
GPU fractal calculation is disabled. The allocation and sustained JVMs run
sequentially, without competing tests, builds or rendering suites.

Navigation uses installed handlers and production base/AA caches. All seed,
forward and return results pass the same exact sample/ARGB controls as the input
baseline. Controls and scene preparation have separate wall-clock intervals;
the process-level memory and CPU observations still include that work. Base
request diagnostics retain generation identity and require worker drain.

Foundation thermal state is available. It is an OS pressure observation, not
temperature, clock frequency or energy. Power-source observations are saved;
background applications and thermal/power conditions are not experimentally
controlled. These runs cannot isolate a thermal cause of timing variation.

## Allocation attribution

[Raw scopes and trials](benchmarks/baseline-9-1-memory-allocation-20260908/workload),
[process observations](benchmarks/baseline-9-1-memory-allocation-20260908/process.csv),
[scope summary](benchmarks/baseline-9-1-memory-allocation-20260908/summary.json),
[allocation groups](benchmarks/baseline-9-1-memory-allocation-20260908/allocations-by-fixture.json).

Eight selected fixtures, 480x270, both requested modes, two navigation cycles
per view and two complete rounds produce **144 verified trials in 80.47 s**.
The trial workload is 33.58 s navigation, 28.35 s controls and 17.84 s source
preparation/control; the remaining interval includes checkpoints and recording.
The JFR and its SHA-256 are preserved locally; the derived grouping is versioned.

The estimated JFR allocation weights total **41.57 GB in navigation scopes**,
33.02 GB in controls and 12.78 GB in source preparation/control. These are
sampled cumulative allocations, not simultaneously resident memory. Navigation
also includes driver snapshots and event bookkeeping. Grouping by the nearest
application stack frame yields the following attribution within navigation:

| Nearest application frame | Estimated allocation weight | Share |
|---|---:|---:|
| `InteractiveAntialiasService.sampleDeepGrid` | 11.45 GB | 27.54% |
| `PreciseSampler.sample` | 9.76 GB | 23.49% |
| `ReferenceOrbit.cRealAsDouble` | 4.89 GB | 11.77% |
| `ReferenceOrbit.cImaginaryAsDouble` | 4.66 GB | 11.21% |
| `MandelbrotFormula.calculate` | 2.36 GB | 5.67% |
| `PreciseRenderGrid.realAt` | 2.05 GB | 4.93% |
| `PreciseRenderGrid.rawImaginaryAt` | 1.56 GB | 3.75% |

These groups are exclusive nearest-frame attribution, not inclusive call-tree
costs. JFR weights can represent earlier allocations on the same thread;
time-window joins are approximate at boundaries. They neither establish exact
allocation counts nor quantify the speedup achievable by removing them.

Code inspection corroborates the mechanism: `sampleDeepGrid` constructs precise
subpixel coordinates, `PreciseSampler.sample` subtracts the reference coordinates,
and the two immutable reference-coordinate accessors call `BigDecimal.doubleValue`
again for each sample. This motivates the explicit follow-up in 9.4: first
evaluate memoizing those immutable conversions, then exact coordinate component
reuse, preserving operation order and numerical controls.

Navigation-scoped GC counters accumulate 253 ms over 33.58 s; the profile does
not show that GC pauses alone explain the arithmetic/allocation cost. Primitive
integer arrays in these stacks often belong to arbitrary-precision arithmetic,
so treating every `int[]` allocation as a pixel-buffer copy would be incorrect.

## Sustained run

[Final summary](benchmarks/baseline-9-1-memory-sustained-final-20260908/summary.json),
[raw scopes](benchmarks/baseline-9-1-memory-sustained-final-20260908/workload/scopes.csv),
[RSS/thermal observations](benchmarks/baseline-9-1-memory-sustained-final-20260908/process.csv),
[final NMT difference](benchmarks/baseline-9-1-memory-sustained-final-20260908/nmt-final.json).

The final JFR-free run uses 1512x982, both requested modes and one navigation
cycle per view. Four complete rounds produce **144 verified trials in 794.69 s
(13.24 minutes)**. Three rounds ended just before the requested 600 seconds,
so the runner completed a fourth whole round. Resize cases reach 1640x1054.
NMT and input/scheduling diagnostics remain enabled; this is not an
uninstrumented timing run.

| Observation | Result |
|---|---|
| Heap after each detached-GC checkpoint | 126.931 / 126.951 / 126.946 / 126.951 MB |
| First-to-final detached heap difference | +20,208 bytes |
| Non-heap at the same checkpoints | 40.36 → 43.78 MB |
| Maximum observed process RSS | 2,992.28 MB |
| Final observed process RSS, before JVM exit | 683.54 MB |
| Final NMT total committed | 589,436 KiB |
| Final NMT Java heap committed | 458,752 KiB |
| Foundation thermal state | `nominal` at every observation |

MB denotes decimal bytes / 1,000,000; NMT's KB units are KiB. The observed
detached heap does not accumulate over these four rounds. RSS has large
workload peaks and falls afterwards; its final value is not the live heap.
NMT total committed falls by 1,263,422 KiB from the early-process baseline,
primarily because of heap decommit. Code and metaspace commitments rise by
2,524 and 1,866 KiB respectively. These warmup-sensitive differences are not
independent proof of native leak absence. The checkpoint does not force all
JavaFX peer/resource cleanup to complete on a particular pulse.

![RSS and detached heap checkpoints](benchmarks/baseline-9-1-memory-sustained-final-20260908/memory.svg)

Exact-target timing groups are saved per round. For example, deep-AA pinch
Fast completion is 5156.50 / 5107.67 / 5012.09 / 5055.69 ms, while the Fast
direct/deep/reverse return is 7336.99 / 7469.16 / 7276.41 / 7799.82 ms.
This shows why nominal thermal pressure and a bounded heap do not establish
constant latency for every scenario. There is one observation per case/round;
warmup, GC and scheduling remain mixed. No stable p95, thermal-causality or
before/after speedup claim is made.

## Validation and limits

The summarizer checks ordered rounds, every source/target pair and raw input
generation, completion/publication timestamps, request drain accounting, scope
identity and chronology, requested duration, checkpoints, RSS and both NMT
captures. Regression cases reject missing controls/checkpoints, incomplete
rounds, scope overlap, short runs and failed native captures. JFR tests cover
nanosecond timestamps with timezone offsets and allocation scope boundaries.

- `mvn clean test`: **394 tests, zero failures/errors, 37 skipped**.
  [Saved log](benchmarks/baseline-9-1-memory-sustained-final-20260908/clean-test.log).
- Python baseline validators: **22 tests passed**.
  [Saved log](benchmarks/baseline-9-1-memory-sustained-final-20260908/python-tests.log).
- Graphical `BaselineInputBenchmarkTest`: **16 tests passed**.
  [Summary](benchmarks/baseline-9-1-memory-sustained-final-20260908/fx-test-summary.txt),
  [log](benchmarks/baseline-9-1-memory-sustained-final-20260908/fx-tests.log).
  The logged injected timer failure is the expected asynchronous-failure regression.
- Both accepted datasets pass the strict memory/event/drain validator:
  **288 trials total**, excluding seed and independent control work.
  A short-run regression also verifies that the monitor waits for its initial
  NMT capture even when the workload finishes first.
  [Validation record](benchmarks/baseline-9-1-memory-sustained-final-20260908/validation.txt).

The first restricted graphical probe failed to access a display/NMT. Temporary
development probes under `target` are excluded from accepted datasets and are
removed by the final clean build.
The initial sustained run at
`benchmarks/baseline-9-1-memory-sustained-20260908` was stopped to isolate
per-case stack lifetimes; its `SUPERSEDED` marker excludes it from conclusions.
It has no monitor success marker. Historical CSVs are not overwritten.
New process CSVs are normalized to LF for review; their original CRLF byte
streams are retained as `process-original.csv.gz`. Numeric observations are
unchanged. Saved log trailing whitespace is normalized separately.

The eight-fixture selection is not full formula/feature coverage, and a bounded
soak does not prove indefinite memory stability. NMT excludes some native graphics
and third-party allocations; committed memory and RSS have different meanings.
Forced GC checkpoints do not describe normal interactive GC cadence. Physical
scanout, temperature sensors, clock throttling and energy remain unmeasured.
The final uninstrumented 30-pair/multiple-process decision protocol in 9.1
remains open; these diagnostic timings cannot close that gate.
