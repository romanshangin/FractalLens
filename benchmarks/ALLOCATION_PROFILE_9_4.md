# Roadmap P1.4 allocation profile — 2026-09-24

> Historical evidence: results and “current source” below refer to the dated
> experiment, not a new validation of this checkout. Only the
> [selected evidence](README.md) is included; entries marked “private archive”
> are unavailable in this repository. Original SHA values and recorded commands
> describe the original run and may require private revisions or inputs. See
> [provenance and replay limits](../PUBLICATION.md).

## Decision and scope

**Profile complete.** The current renderer still allocates heavily in both
immutable reference-coordinate conversion and precise deep antialiasing (AA)
coordinate construction. The former also appears in non-AA deep navigation.
These are concrete candidates for the first 9.4 experiments. This is one
instrumented diagnostic run, not a control/candidate comparison. No production
optimization or timing-based retain/reject decision is made here; the 9.4
optimization and promotion gates remain open.

The source is `03b2f105ea6141d06668f232c1456c74eb127c37` on
`codex/p1-4-allocation-profile`, matching `main` before this documentation
change. The [source SHA-256 manifest](p1-4-allocation-profile-20260924/source-sha256.json)
records the compiled source and driver inputs. The production renderer and
benchmark driver were not changed for this profile.

## Fixed diagnostic run

The existing [memory benchmark protocol](BASELINE_MEMORY_BENCHMARK.md) was run
in an active graphical macOS session. It used Apple M3 Pro, macOS 27.0, Homebrew
OpenJDK 26.0.2, JavaFX 26.0.2, the ES2 Prism pipeline at 2x scale, a 4 GiB
maximum heap and AC power. The OS thermal pressure probe reported `nominal`
throughout; it is not a temperature or frequency measurement. Fractal GPU
calculation was disabled.

The selected `9.1-v1` fixtures were `seahorse-fixed`, `julia-aa-regular`,
`deep-glitch-aa`, `direct-deep-reverse`, `pan-25`, `resize-then-drag`,
`cancel-direct` and `cancel-deep`, all at 480x270. Both requested `FAST` and
`REFINED` modes ran for two navigation cycles per view and two complete rounds.
Deep requests can use effective Fast publication even when Refined was
requested. The JFR `profile` recording and NMT were enabled. Navigation includes
input-driver snapshots and event bookkeeping; exact controls and seed work are
separate scopes.

The measured process command was:

```sh
python3 scripts/run_baseline_memory.py benchmarks/p1-4-allocation-profile-20260924 --java /opt/homebrew/opt/openjdk/bin/java --seconds 60 --cycles 2 --sizes 480x270 --fixtures seahorse-fixed,julia-aa-regular,deep-glitch-aa,direct-deep-reverse,pan-25,resize-then-drag,cancel-direct,cancel-deep --modes FAST,REFINED --thermal target/p1-4-baseline-thermal --jfr
python3 scripts/summarize_baseline_memory.py benchmarks/p1-4-allocation-profile-20260924 --jfr /opt/homebrew/opt/openjdk/bin/jfr
```

Before the run, compilation and classpath preparation passed:

```sh
mvn -q -DskipTests test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/baseline-classpath.txt
```

The thermal helper was built from `scripts/baseline_thermal.swift` with
`/usr/bin/swiftc`. Output directories must be fresh when reproducing this
command. The exact JDK path is the one used locally; select an equivalent JDK
and its matching `jfr` on another machine.

The first attempt in the restricted process environment could not initialize
JavaFX: all Prism pipelines failed to find a screen and
`Screen.getMainScreen` saw an empty list. Its NMT attach then timed out. It
produced no validated workload and is excluded from the profile. An unchanged
rerun in the active desktop session produced the accepted dataset below.

## Validation and sampled allocation attribution

The strict summarizer accepted [144 trials across two rounds](p1-4-allocation-profile-20260924/summary.json)
in 87.10 seconds. All seed, forward and return frames passed exact CPU sample
and ARGB controls. The workload `SUCCESS` and process `MONITOR_SUCCESS` markers,
both NMT captures, ordered scope/event coverage and the JFR-derived
[allocation groups](p1-4-allocation-profile-20260924/allocations.json) are
saved with the [environment record](p1-4-allocation-profile-20260924/environment.json).
The raw JFR was retained privately as
`p1-4-allocation-profile-20260924/allocation.jfr` and ignored by Git; its
SHA-256 is `f6732ed75f4f3a67e645af69195d47e63126bd534b27a7d4832f6ac7fe5a6e94`.
The derived allocation groups are versioned and can be regrouped without the
raw recording. The privately archived launch log also records the existing native loading-material
fallback; it did not prevent exact frame validation.

The table groups `jdk.ObjectAllocationSample` estimated weights by the nearest
application frame **within navigation scopes**. These groups are exclusive by
that grouping rule, but sampled weights are estimates, not exact bytes allocated
by a method, retained memory, or a potential speedup. A sample's weight can
include earlier allocations on the same thread, including across a scope
boundary.

| Nearest application frame or group | Estimated weight | Navigation share | Sampled events |
|---|---:|---:|---:|
| `ReferenceOrbit.cRealAsDouble` + `cImaginaryAsDouble` | 7.471 GB | 22.9% | 1,915 |
| `InteractiveAntialiasService.sampleDeepGrid` | 9.174 GB | 28.2% | 703 |
| `PreciseSampler.sample` | 7.220 GB | 22.2% | 522 |
| `PreciseRenderGrid.realAt` + `rawImaginaryAt` | 2.185 GB | 6.7% | 161 |
| All navigation frames | 32.589 GB | 100% | — |

The combined `deep-glitch-aa` navigation scopes account for 26.174 GB of the
32.589 GB estimate. Within that fixture, the reference conversion group is
6.150 GB, deep AA coordinate construction is 9.174 GB, the precise sampler's
delta construction is 7.220 GB, and precise grid setup is 2.168 GB. The
reference conversion group also appears in `direct-deep-reverse` (0.821 GB)
and `cancel-deep` (0.500 GB); the deep AA coordinate group appears only in
`deep-glitch-aa`. No weight in either candidate group was attributed to the
selected direct seahorse or regular Julia fixtures.

Code inspection explains these stacks. `ReferenceOrbit` stores immutable
`BigDecimal` centre coordinates, while its `cRealAsDouble` and
`cImaginaryAsDouble` accessors call `doubleValue()` again for each sample.
`sampleDeepGrid` constructs two exact offset coordinates per subpixel with
`BigDecimal.valueOf`, multiplication and add/subtract; `PreciseSampler.sample`
then subtracts the reference centre with the render `MathContext`. The sampled
classes in these groups are mainly `int[]`, `BigInteger`, `MutableBigInteger`
and `BigDecimal`, consistent with arbitrary-precision arithmetic rather than
pixel-buffer copies.

## Next experiment boundary

Evaluate memoizing the two reference `double` values once per completed
`ReferenceOrbit`, then exact coordinate component reuse in deep AA. Each
candidate must retain decimal coordinate authority, the same `MathContext`
operation order, cancellation and reference-cache ownership, and exact
sample/ARGB controls. Use frozen control/candidate builds and the
[alternating paired protocol](BASELINE_PAIRS_BENCHMARK.md) for any timing-based
retain/reject decision, including base/AA publication and the relevant
non-deep control workloads. Separate JFR runs can show whether allocation sites
move; they cannot establish a latency gain. A realistic-window and sustained
gate is still required before promoting a retained change under 9.9.
