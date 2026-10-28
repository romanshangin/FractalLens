# Prism pipeline comparison: ES2 (OpenGL) and Metal on macOS

> Publication scope: use this protocol for fresh runs in a new output directory.
> Keep raw logs, CSV recordings and JFR files private; publish only reviewed
> summaries. See [evidence provenance](../docs/development/PUBLICATION.md).

JavaFX 26 tries the ES2 (OpenGL) pipeline first on macOS and Metal (`mtl`)
second. The application does not choose one, so it currently runs on ES2. On
ES2, a frame finished while the window is on another macOS Space can stay
invisible after returning to that Space; the same scenario was not reproduced
with `-Dprism.order=mtl` in a manual check. This protocol measures whether
Metal can replace ES2 without making publication or visible updates slower.

One frozen build runs under both pipelines. A always uses
`-Dprism.order=es2`, B always uses `-Dprism.order=mtl`; each JVM requests a
single pipeline, so a failed initialization cannot silently fall back to
another one. Ratios are B/A, Metal over ES2.

The comparison has three parts. Only the first two produce timing verdicts.

| Part | Driver | What it reaches | Policy |
| --- | --- | --- | --- |
| Publication pairs | [`BaselineFxBenchmark`](BASELINE_FX_BENCHMARK.md) through `run_baseline_pairs.py` | FX-thread publication and the next post-layout pulse | [`prism-pipeline-fx.json`](policies/prism-pipeline-fx.json) |
| Screen latency pairs | [`InteractionLatencyBenchmark`](INTERACTION_LATENCY.md) through `run_pipeline_latency.py` | JavaFX Robot capture of a publication marker (compositor proxy) | [`prism-pipeline-latency.json`](policies/prism-pipeline-latency.json) |
| Pulse phases | The same latency driver with JFR, without screen capture | `javafx.PulsePhase` durations on the FX and render threads | Diagnostic runs declared by the latency policy |

The publication pairs measure software boundaries on the FX thread. Texture
upload, drawing and presentation happen on the render thread, so these metrics
can miss a pipeline difference; they detect contention or pulse delays that
reach the FX thread. The screen pairs include rendering and compositing up to a
captured marker, which is the closest available proxy for a visible update. The
pulse phases attribute render-thread time (`Painting`, `Presenting` and the
per-pulse total) but run under JFR and are therefore excluded from verdicts.

## Running

Freeze one revision, then run the two campaigns sequentially without other
timing work, builds or tests. Both campaigns open windows; the latency campaign
keeps a window on top and needs screen-recording permission for the selected
`java` executable. Do not use the computer while it runs. On macOS, prefix the
runner with `caffeinate -d -i -u --` so display sleep, and the screen lock that
follows it, cannot hide the window; a hidden window aborts the latency driver.

```shell
python3 scripts/benchmarks/prepare_baseline_build.py HEAD /tmp/fractallens-prism-build \
  --java /path/to/jdk/bin/java --maven "$PWD/mvnw"

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmarks/run_baseline_pairs.py \
  benchmarks/policies/prism-pipeline-fx.json NEW_FX_OUTPUT \
  --a /tmp/fractallens-prism-build --b /tmp/fractallens-prism-build \
  --java /path/to/jdk/bin/java --thermal /tmp/fractallens-baseline-thermal
PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmarks/summarize_baseline_pairs.py NEW_FX_OUTPUT

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmarks/run_pipeline_latency.py \
  benchmarks/policies/prism-pipeline-latency.json NEW_LATENCY_OUTPUT \
  --build /tmp/fractallens-prism-build \
  --java /path/to/jdk/bin/java --thermal /tmp/fractallens-baseline-thermal
PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmarks/summarize_pipeline_latency.py \
  NEW_LATENCY_OUTPUT --jfr /path/to/jdk/bin/jfr
```

The thermal helper is optional; build it as described in
[the paired protocol](BASELINE_PAIRS_BENCHMARK.md).

## Configuration campaigns

`run_baseline_pairs.py` accepts `purpose: configuration`: both snapshots must be
the same compiled build, `jvm_options` declares exactly one
`-Dprism.order=es2|mtl|sw` option per label and `expected_pipeline` must match it.
Every launch must carry its declared option, and its log (`-Dprism.verbose=true`)
must report exactly that initialized pipeline. Control-only policies are allowed:
the question is non-inferiority, not a required improvement. All other
validation of the [paired protocol](BASELINE_PAIRS_BENCHMARK.md) applies,
including AB/BA alternation, frozen policy hashes and matching sample and ARGB
fingerprints, which are computed from buffers and must be identical across
pipelines.

`run_pipeline_latency.py` alternates AB/BA JVMs of the interaction-latency
driver with screen observation for every mode (Refined, Fast, Base only) and the
declared gestures. It uses the same native-access options as the macOS
`javafx:run` profile and exports the benchmark package to the JavaFX launcher.
After the timed processes, it runs the declared number of JFR diagnostic JVM
pairs without screen capture. The analyzer requires the declared pipeline in
every log and recording header, screen observation in timed runs, one window
size and output scale for the whole campaign, and every declared trial. It
pairs trials by process pair, mode, gesture and iteration. A trial whose marker
is absent on either side is counted in `missing_pairs`, never as zero; a group
with fewer than 30 pairs is inconclusive. Every declared mode, gesture and
metric is reported, so a metric absent from every trial stays in the result as
an inconclusive group with zero pairs.

## Decision rule

Every declared metric is a control: the hierarchical bootstrap 95% interval of
the median B/A ratio must stay at or below 1.05. `timing_verdict` is `pass` only
when every group passes, `fail` when any group's interval lies entirely above
its limit and `inconclusive` otherwise. A timing pass does not by itself switch
the application to Metal. A switch also needs the visible checks
([Space switching](SPACE_SWITCH_VALIDATION.md), resize, fullscreen, light and
dark appearance) and the portable
suite, and it applies only to the measured Mac. Windows and Linux do not use
Metal.

## Limits

- Results apply to the measured Mac model, macOS, JDK and JavaFX version.
- Screen observation is a capture of a 144x16 marker strip, not physical scanout
  or photon latency, and capture cost is included in each observation.
- FX publication metrics end on the FX thread and do not include GPU work.
- JFR pulse phases are diagnostic; JFR itself changes timing.
- Power source and thermal pressure are recorded only before and after each JVM.
