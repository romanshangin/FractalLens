# Roadmap 9.1 allocation and sustained-memory diagnostics

`BaselineMemoryBenchmark` reuses the installed-handler input driver and the
decimal-authoritative `9.1-v1` fixtures. This is a diagnostic workload, not an
uninstrumented before/after performance gate. Production rendering is unchanged.

## Workload and ownership

A round visits every selected size, fixture and requested Fast/Refined mode.
It creates and verifies a source view, repeats navigation in that same view
with its production caches, and navigates back toward the source between cycles.
Multi-step cases cover pan, direct/deep/reverse and resize/drag. Single-step
cases use pinch; cancellation cases replace a submitted render with new wheel
input. A resize return changes dimensions through the real handler; it does
not silently assign the source camera. Actual jobs remain authoritative.

Every seed and every forward/return frame is checked against the existing exact
CPU sample and ARGB controls. The next gesture uses this verified source.
Repeated gestures can accumulate floating-point camera rounding and cache
history; the report does not require different cycles to have identical hashes.
It does require complete ordered coverage, actual source/target jobs, matching
input/render generations and agreement of timings with raw events.

At the end of each round the view is closed and detached, then the harness
requests GC and waits 200 ms. This deliberately separates post-detach heap
observations from navigation timing. It is not a measurement of ordinary GC
cadence or proof that every native graphics resource has already been released.
Per-case frame results live in a helper method which returns before this
checkpoint. JavaFX may still release scene-graph/graphics state on later pulses;
the checkpoint is a repeatable observation, not a claim of zero retained frames.
Every run completes at least two whole rounds and the requested minimum duration.
Duration is a lower bound: the last whole round and exact controls may extend it.

## Boundaries and recorded evidence

- `workload/scopes.csv`: disjoint wall-clock/monotonic intervals for
  `seed_and_control`, `navigation`, `control` and `detached_gc_checkpoint`.
  Heap-before/after, non-heap, direct/mapped buffer accounting and GC deltas
  describe observations, not allocated bytes. Navigation includes the driver's
  source/output snapshots and event recording as well as real UI work.
- `workload/samples.csv`, `events.csv`, `actual-manifest.csv`: production input
  timings and exact jobs, with the same meaning as the input baseline. Base
  scheduling diagnostics wait for cancelled worker drain. They do not measure
  physical scanout or per-thread CPU utilization.
- `process.csv`: RSS and process CPU from `ps`, approximately every two seconds;
  CPU can exceed 100% on multiple cores. Startup/final hold observations remain
  present. Missing RSS prevents accepting a run.
- `gc.log`: normal GC and explicitly requested checkpoint GC with timestamps.
- `nmt-baseline.json` and `nmt-final.json`: JDK Native Memory Tracking summary
  baseline after at least five seconds and final difference while the JVM is
  still alive, after the final detached checkpoint. Reserved memory is address
  space; committed memory is not identical to RSS. NMT does not account for all
  third-party/graphics native allocations. Initial warmup is not a steady-state
  native-memory baseline; use the RSS time series and repeated checkpoints too.
- `thermal_state`: Foundation `ProcessInfo.thermalState` on macOS. It reports
  OS pressure (`nominal`, `fair`, `serious`, `critical`), not temperature, clock
  frequency, watts or a causal explanation of timing changes. Unavailable data
  remain explicitly unavailable. Power-source and `pmset` notes are saved.
- `environment.json`, `fx-environment.txt`, launch log and source SHA-256 manifest:
  exact JDK executable, JVM arguments, platform, actual JavaFX pipeline, scale,
  source revision and working-tree content. No automatic JDK switching.

The shared process lock excludes competing baseline suites. Run tests, builds,
JFR conversion and other rendering benchmarks outside the diagnostic run.
Uncontrolled background activity remains a limitation.

## Separate allocation attribution

Use `--jfr` in a separate process. The JDK `profile` recording collects
`jdk.ObjectAllocationSample`. Its `weight` is an estimate of allocated bytes,
not an exact object count, retained memory or pause duration. The summarizer
joins timestamps to diagnostic scopes and groups by allocated class and nearest
application stack frame. Weights can include earlier allocations on that thread,
so a scope boundary is not an exact accounting cut. Driver snapshot allocations
inside navigation and shared production code invoked by controls must still be
distinguished when interpreting stacks. Asynchronous activity may cross scopes.

The raw JFR stays in the output directory but is ignored by Git; keep its hash
with the saved derived report. JSON conversion is temporary and removed after
successful analysis. Small raw CSVs, logs and reports can be versioned.

## Reproduce

Run in a graphical macOS session with the selected JDK, sequentially:

```sh
mvn -q -DskipTests test-compile dependency:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile=target/baseline-classpath.txt
/usr/bin/swiftc -module-cache-path /tmp/fractalui-swift-module-cache \
  scripts/baseline_thermal.swift -o target/baseline-thermal

python3 scripts/run_baseline_memory.py target/new-allocation-run \
  --java /path/to/jdk/bin/java --seconds 60 --cycles 2 \
  --thermal target/baseline-thermal --jfr
python3 scripts/summarize_baseline_memory.py target/new-allocation-run \
  --jfr /path/to/jdk/bin/jfr

python3 scripts/run_baseline_memory.py target/new-sustained-run \
  --java /path/to/jdk/bin/java --seconds 600 --cycles 1 \
  --sizes 1512x982 --thermal target/baseline-thermal
python3 scripts/summarize_baseline_memory.py target/new-sustained-run

# Optional report figure, using a Python environment with matplotlib:
python3 scripts/plot_baseline_memory.py target/new-sustained-run \
  target/new-sustained-run/memory.svg

python3 -m unittest discover -s scripts -p 'test_summarize_baseline*.py'
```

Output directories and summaries must be new. `workload/SUCCESS` establishes
frame conformance; `MONITOR_SUCCESS` additionally requires successful JVM exit
and both NMT captures. Only the strict summary validates complete scope/event
coverage. Failure leaves its evidence intact and cannot pass the monitor gate.

The default eight fixtures cover seahorse, Julia AA, deep AA, direct/deep/reverse,
25% overlap pan, resize/drag, direct cancellation and deep cancellation. This
selection is not the full 28-fixture matrix. Extend size, formula, duration and
long-lived-view coverage before extrapolating to untested workloads/platforms.
