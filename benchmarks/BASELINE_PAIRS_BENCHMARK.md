# Alternating build comparisons for roadmap 9.1

> Publication scope: use this protocol for fresh runs in a new output directory.
> Keep raw logs and diagnostic captures private; publish only reviewed summaries
> and necessary sanitized evidence. See [evidence provenance](../docs/development/PUBLICATION.md).

The paired runner compares frozen builds through the existing
[headless](BASELINE_BENCHMARK.md) or [JavaFX publication](BASELINE_FX_BENCHMARK.md)
driver. It records individual matching samples, including their process pair and
launch order. Allocation/JFR and [sustained memory diagnostics](BASELINE_MEMORY_BENCHMARK.md)
remain separate runs. See [the A/A calibration](BASELINE_PAIRS_VALIDATION.md).

## Freeze inputs before measurement

Build two explicit, locally available Git revisions with the same JDK. The
preparation script archives committed source without changing the checkout,
builds offline, and copies compiled classes and ordered dependencies into new
self-contained directories. Uncommitted changes are excluded.

```shell
python3 scripts/prepare_baseline_build.py BASE_COMMIT /tmp/fractallens-build-a \
  --java /opt/homebrew/opt/openjdk/bin/java
python3 scripts/prepare_baseline_build.py CANDIDATE_COMMIT /tmp/fractallens-build-b \
  --java /opt/homebrew/opt/openjdk/bin/java
```

`build.json` records the resolved revision, source/compiled/dependency hashes,
JDK executable hash and version, and build command. Dependencies preserve their
classpath order. The launcher verifies compiled files before and after the
campaign and rejects changes to the fixture/benchmark driver or dependencies
between builds. The snapshots must remain available to repeat the exact binary
run; result directories contain their manifests, not copies of the binaries.

Copy a policy from `benchmarks/policies/` to a new file and review it **before**
launching. Set `purpose` to `candidate` for different builds or `calibration` for
the same compiled build on both sides. Declare selected fixtures, render sizes,
modes, warmups, samples, process pairs, timeout and every target/control metric.
Targets require at least a 10% improvement (B/A <= 0.90); controls allow at most
5% regression (B/A <= 1.05), following the proposed CPU criteria in
[the optimization analysis](CPU_GPU_OPTIMIZATION_ANALYSIS.md). Stricter limits
are allowed. A result is scoped to
these declared cases; it cannot establish a whole-matrix or whole-app benefit.

## Execute without competing work

```shell
swiftc scripts/baseline_thermal.swift -o /tmp/fractallens-baseline-thermal
PYTHONDONTWRITEBYTECODE=1 python3 scripts/run_baseline_pairs.py POLICY.json NEW_OUTPUT \
  --a /tmp/fractallens-build-a --b /tmp/fractallens-build-b \
  --java /opt/homebrew/opt/openjdk/bin/java \
  --thermal /tmp/fractallens-baseline-thermal
PYTHONDONTWRITEBYTECODE=1 python3 scripts/summarize_baseline_pairs.py NEW_OUTPUT
```

The calibration policies use three fresh process pairs and ten measured
sequences per build per process. The original bounded-mask candidate policies
use six process pairs and five measured sequences; the final P0.3 policies use
30 process pairs and one measured sequence. All use three warmups and one
additional cold sequence. Launch order starts AB, BA, AB and continues
alternating. Sample 0 of A pairs with sample 0 of B within the same process
pair, fixture, step, dimensions and mode.
These are blocks of JVM runs, not a new JVM for each sample. With an odd number
of process pairs the two launch orders are not equally represented; the report
preserves order and per-process statistics to expose drift.

The launcher strips injected Java/Maven option variables, fixes the heap at
4 GiB, disables AA/scheduling instrumentation, GPU dispatch and native-memory
tracking, and launches Java directly after building. Selected-JDK and fixture
preflights run before timing. JavaFX requires graphical-session access; its log
records the selected Prism pipeline. Each JavaFX JVM uses a fresh native cache
inside its output directory. The cache files are ignored by that directory's
`.gitignore`; the launch record preserves the path.

A parent POSIX record lock holds the default Java benchmark lock for the entire
campaign. Children use a private temporary directory for their own lock. Other
baseline drivers using the same default temporary directory cannot interleave;
external tools or a deliberately different temporary directory bypass this
coordination. Avoid concurrent builds, tests, profilers and timing suites.

Raw heap/GC observations remain in each driver's CSV. Per-child resource usage
records peak RSS and CPU time. macOS power-source and thermal-pressure readings
are taken only before/after each JVM, so they do not establish the thermal state
throughout the run. A missing thermal helper is reported as unavailable. Physical
temperature, energy and scanout are unmeasured.

## Validation and interpretation

All cold, warmup and measured rows must be present exactly once. The validator
checks the frozen policy hash, AB/BA launch order, successful JVM exits, matching
runtime/heap/workload/dispatch, valid timings and stable non-cancelled sample/ARGB
fingerprints. JavaFX additionally requires its existing exact-control validator
and `SUCCESS` marker. Partial cancelled-frame fingerprints are intentionally
excluded; cancellation trigger/tail measurements must exist.

`CAMPAIGN_COMPLETE` means structural validation succeeded. `pairs.csv` preserves
all declared pairs; `analysis.json` records medians, descriptive p95 values,
median matched B/A ratios, per-process ratios and a seeded hierarchical bootstrap
interval (5,000 resamples, process pairs first and then samples within them).
Uncertainty resolution depends on the number of process clusters; 30 samples
do not establish a stable p95 guarantee. Inspect process/order effects and use
a new, larger predeclared campaign when necessary, preserving earlier results.

For candidates a timing metric passes only when the upper interval bound is
within its limit, fails when the lower bound exceeds it, and is otherwise
inconclusive. Every declared group must pass for an aggregate timing pass.
Calibration always reports `calibration_only`, even if random differences look
like an improvement. Neither verdict automatically promotes production code.

Fingerprints supplement, but do not replace, exact sample-by-sample numerical
and lifecycle regression tests. Headless `returned_argb_ms` ends at returned
pixels; JavaFX publication ends at PixelBuffer publication. Neither measures a
physical gesture reaching the display. Headless cancellation is triggered after
a completed backend region, so planning cancellation needs its separate probe.
The process/lock launcher currently requires POSIX; this calibration is macOS
evidence and does not replace Windows validation of production changes.
An actual ValidityMask change must retain those correctness checks and obtain
scope-matched input/publication and memory/thermal evidence before acceptance.

Outputs and summaries refuse existing destinations. Keep raw failures; start a
fresh campaign rather than deleting an unfavorable sample or modifying policy
after measurement.
