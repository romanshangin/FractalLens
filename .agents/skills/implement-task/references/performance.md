# Performance implementation protocol

Read this reference for performance implementation work. Follow the relevant
project benchmark and validation documents for subsystem-specific gates.

Treat an unmeasured performance change as an optimization candidate, not a
confirmed optimization.

Before implementation:

- identify the suspected bottleneck;
- select fixed workloads and a control implementation or baseline code state;
- define the correctness gate and retain/reject metrics;
- reuse an established benchmark protocol when one exists. Start from the
  index in `benchmarks/README.md`. For CPU retain/reject decisions, the
  established method compares frozen builds with
  `scripts/benchmarks/prepare_baseline_build.py` and
  `scripts/benchmarks/run_baseline_pairs.py` under
  `benchmarks/BASELINE_PAIRS_BENCHMARK.md`, with a predeclared policy in
  `benchmarks/policies/`. Take pair counts, process starts, and thresholds
  from that protocol or the roadmap item rather than choosing them after
  seeing results.

Initial bottleneck diagnosis may use lightweight exploratory measurements. Apply
the controlled protocol below before using results for a `retain` or `reject`
decision:

- compare identical scenes, dimensions, iteration limits, antialiasing settings,
  and precision modes;
- record backend, JDK, JavaFX, LWJGL, hardware, power state, inputs, and code
  state;
- use a fresh output directory and separate warm-up from measured repetitions;
- measure first-visible latency, completion time, and publication latency when
  they affect users;
- validate rendered output as well as timing;
- use at least three repetitions for basic reproducibility and enough
  alternating control/candidate pairs and JVM starts for a retain/reject
  decision;
- do not infer a memory leak from RSS, JFR, NMT, or one short run alone;
- do not compare runs collected under materially different thermal or power
  conditions.

Stop tuning once the predefined retain/reject threshold is clearly met or the
agreed experiment budget is exhausted. Do not repeatedly vary unrelated
parameters until a favorable result appears.

After the experiment, record an explicit `retain`, `reject`, or `inconclusive`
decision. Do not leave a rejected or inconclusive candidate active in the final
implementation. Keep the commands, parameters, summary, and necessary raw
evidence in the appropriate validation or results document, following the
privacy and publication rules in `AGENTS.md`.
