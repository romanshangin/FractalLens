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

## Predeclared confirmation campaign (P1.7)

The first campaign left 37 of 103 groups inconclusive. The confirmation
policies retain the entire original matrix, including the 66 passing groups;
no group is selected by its observed median. The original policies and results
remain historical evidence. The new policies change only `process_pairs`:

| Part | Confirmation policy | JVM pairs | Samples per group | Warmups per case |
| --- | --- | ---: | ---: | ---: |
| Publication | [`prism-pipeline-fx-confirm.json`](policies/prism-pipeline-fx-confirm.json) | 60 | 180 | 3 |
| Screen latency | [`prism-pipeline-latency-confirm.json`](policies/prism-pipeline-latency-confirm.json) | 30 | 150 | 2 |

Publication has twice the original independent process pairs; screen latency
has five times the original independent process pairs. Within-JVM sample
counts remain 3 and 5 respectively; warmups apply to each fixture/mode or
gesture/mode case, not to the JVM as a whole. More pairs improve precision but do not
guarantee a decisive result. Both policies retain AB/BA alternation, all
fixtures, sizes, modes, gestures, metrics, pipeline checks and the 1.05 limit.
The latency campaign retains its separate JFR diagnostic pair; those diagnostics
do not explain the 3024x1964 publication outliers because they use a different
workload and window size.

Freeze revision `f8895b6c136ebfdf9eb8aebc39622196d36d99e2` once for both
pipelines, using the same JDK and dependencies for compilation and execution.
Use the commands in **Running**, substituting that revision for `HEAD`, the
confirmation policies for the original policies, and fresh output directories.
Prefix **both** timing runners with `caffeinate -d -i -u --`. Run them sequentially
in an active graphical macOS session with AC power, nominal thermal pressure,
a configured thermal helper, and no competing builds, tests or desktop use.
Keep the latency window at 1200x760 logical points and output scale 2, matching
the first campaign. Inspect actual pipeline initialization and the recorded
environment rather than assuming requested settings were applied.

The budget is one complete campaign per confirmation policy. Do not inspect
intermediate timing verdicts, extend the sample budget, relax a limit, drop a
group, or pool historical samples into the confirmation result. If a campaign
aborts because of visibility, permission, pipeline, power or thermal problems,
retain it privately as invalid evidence, correct the environment, and restart
the same policy in a new directory. Never resume with selected process pairs.

Analyze each complete campaign independently using the existing analyzers and
the hierarchical bootstrap decision rule above. Report every group, including
missing pairs. A combined timing pass requires both campaigns to pass. Any
failing group rejects default activation under this performance gate. Otherwise,
any inconclusive group leaves the combined decision `inconclusive`: stop this
budget and request an explicit owner decision on the latency trade-off before
activation. A timing pass still leaves the other migration gates open.

Status: predeclared, not executed. No new timing conclusion or default-pipeline
change follows from these policies. Spaces presentation, fixture-specific JFR
attribution, long-session memory and the remaining migration checklist are
separate open requirements.

## Limits

- Results apply to the measured Mac model, macOS, JDK and JavaFX version.
- Screen observation is a capture of a 144x16 marker strip, not physical scanout
  or photon latency, and capture cost is included in each observation.
- FX publication metrics end on the FX thread and do not include GPU work.
- JFR pulse phases are diagnostic; JFR itself changes timing.
- Power source and thermal pressure are recorded only before and after each JVM.
