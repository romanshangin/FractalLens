# P1.5 retained CPU AA promotion assessment — 2026-09-25 to 2026-09-28

> Historical evidence: results and “current source” below refer to the dated
> experiment, not a new validation of this checkout. Only the
> [selected evidence](README.md) is included; entries marked “private archive”
> are unavailable in this repository. Original SHA values and recorded commands
> describe the original run and may require private revisions or inputs. See
> [provenance and replay limits](../PUBLICATION.md).

## Decision and scope

**P1.5 is complete for the retained CPU antialiasing (AA) change against its
pre-`3454dc7` implementation.** The owner accepted a retained-Julia first-tile
exception on 2026-09-27; all other paired timing and cold-startup criteria
passed on the current source. The first AA tile for retained Julia remains a
measured performance cost, not a passing timing group.
The cache change from `3454dc7` passed portable, macOS JavaFX/native,
exact-frame, and sustained diagnostic checks. Its earlier paired measurements
show a substantial full-frame gain and a retained-Julia first-tile regression.
Seven current-source first-tile candidates were measured against a frozen control.
D passed headless and cold-startup gates but only 16 of 18 JavaFX timing groups
in its final confirmation. E and F passed their first-tile target but did not
establish the retained full-AA control at their predeclared screening gates.
G failed the retained first-tile target in its headless screen.
All candidates were removed; no new production optimization is promoted.

P1.2 and P1.3 rejected their candidates and left no production optimization;
P1.4 only profiled allocation sites. The CPU AA change is the retained P1
optimization requiring this assessment. Future retained optimizations need
their own 9.9 evidence; these results cannot pre-approve them.

The checkout started clean at `a6531c12a752f84acced1f2b9537a09b411fdb88`
on `main`; validation ran on `codex/p1-5`. The diagnostic driver was changed
before the sustained run to use a JavaFX native cache inside its fresh output
directory. The [source hash manifest](p1-5-aa-sustained-20260924/source-sha256.json)
captures that exact driver and the compiled source inputs. A later ignore-rule
addition only excludes extracted JavaFX binaries from the evidence directory.

## Existing paired decision and remaining first-publication issue

The [AA optimization results](CPU_AA_OPTIMIZATION_RESULTS.md) compare three
fresh alternating JVM pairs with 30 measured frames per build and case. Exact
returned pixels matched for every compared frame. Production-service/JavaFX
Retina overview total publication improved from 1378.67 to 332.33 ms in Fast
mode with AA and from 1393.72 to 283.65 ms in Refined mode. The same report
includes first-publication and heap/GC observations, cold process observations,
and narrower headless controls. Those measurements apply to the frozen builds
from 2026-09-05–06; they are not a new A/B comparison at the current source.

The [retained-Julia follow-up](CPU_AA_OPTIMIZATION_RESULTS.md#correctness-and-remaining-work)
measured first AA tile medians of **6.38 → 11.10 ms** while full AA completion
improved **280.18 → 25.91 ms**. P1.2's [on-demand preparation candidate](RETAINED_SNAPSHOT_PREPARATION.md)
preserved exact pixels but failed its joint first/full timing gate and was
removed. The retained-color preparation barrier and its first-tile cost remain.
This prevents treating the 9.2 first-publication condition as passed without
a new passing candidate or an explicit, predeclared acceptance decision.

## Initial current-source checks

| Check | Result | Coverage and limit |
| --- | --- | --- |
| `mvn -Dtest=InteractiveAntialiasServiceTest,AntialiasSampleCacheTest test` | 22 passed | Cache ownership, tile admission, reuse and cancellation. |
| `mvn test` | 447 reported, 393 executed, 54 opt-in skips; no failures | Portable CPU, precision, palette, fallback and lifecycle regressions on this macOS/JDK. Skipped native/FX cases are not counted as passed. |
| `mvn -Dfractal.fx.tests=true -Djavafx.cachedir=/private/tmp/fractallens-p1-5-fx-cache -DreuseForks=false -Dtest=FractalResizeFxTest,BaselineFxBenchmarkTest test` | 31 executed, 2 separately gated fullscreen skips; no failures | Visible JavaFX resize, pan, AA publication, cancellation and exact benchmark controls in the active desktop session. |
| `mvn -Dfractal.fx.tests=true -Djavafx.cachedir=/private/tmp/fractallens-p1-5-native-fx-cache -DreuseForks=false -Dtest=MacContextMenuFxTest,MacLoadingMaterialFxTest test` | 3 executed, 1 separately gated fullscreen/multi-display skip; no failures | Native macOS menu and material lifecycle on this host. GPU device-loss testing is not applicable to the CPU AA change. |
| `python3 -m unittest discover -s scripts -p 'test_summarize_baseline_memory.py'` | 12 passed | Strict sustained-report validator. |

The sustained command ran in the active graphical macOS session, without
competing rendering benchmarks:

```sh
mvn -q -DskipTests test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/baseline-classpath.txt
/usr/bin/swiftc -module-cache-path /private/tmp/fractallens-p1-5-swift-cache scripts/baseline_thermal.swift -o target/p1-5-thermal
python3 scripts/run_baseline_memory.py benchmarks/p1-5-aa-sustained-20260924 --java /opt/homebrew/opt/openjdk/bin/java --seconds 600 --cycles 1 --sizes 1512x982 --fixtures seahorse-fixed,julia-aa-regular,deep-glitch-aa,direct-deep-reverse,pan-25,resize-then-drag,cancel-direct,cancel-deep --modes FAST,REFINED --thermal target/p1-5-thermal
python3 scripts/summarize_baseline_memory.py benchmarks/p1-5-aa-sustained-20260924
```

The [strict summary](p1-5-aa-sustained-20260924/summary.json),
exact-frame marker (private archive: `p1-5-aa-sustained-20260924/workload/SUCCESS`), and
monitor marker (private archive: `p1-5-aa-sustained-20260924/MONITOR_SUCCESS`) passed: 108
verified navigation trials in three complete rounds over 652.00 seconds.
Every source, forward and return frame matched exact CPU sample and ARGB
controls. The run covered normal/deep AA, reuse, reverse navigation, resize,
and direct/deep cancellation at 1512x982, with the resize case reaching its
declared larger dimensions.

The host was an Apple M3 Pro with 18 GiB RAM, macOS 27.0, Homebrew OpenJDK
26.0.2, JavaFX 26.0.2, a 4 GiB maximum heap and 2x JavaFX output scale.
Fractal GPU calculation was disabled. Power was AC at the start and after the
run. The process observations (private archive: `p1-5-aa-sustained-20260924/process.csv`) report
`nominal` OS thermal pressure throughout; they do not measure temperature or
energy. Heap after each forced detached-GC checkpoint was 127.110, 127.166,
and 127.193 MB, a first-to-last increase of 82,432 bytes. Navigation scopes
totaled 218.72 seconds with 2.684 seconds of recorded GC time. Peak observed
RSS was 3,118.76 MB and the final pre-exit observation was 598.98 MB.
NMT baseline and final captures (private archive: `p1-5-aa-sustained-20260924/nmt-final.json`)
completed. These are bounded diagnostic observations, not a proof of indefinite
memory stability or a candidate-versus-control memory improvement.

The diagnostic launch logged the existing loading-material fallback because
its direct JVM command does not export an internal JavaFX package. The separate
native material test passed under Maven's configured exports. This fallback
did not invalidate exact frame controls, and it is not evidence that the native
material passed in the diagnostic JVM.

## Interim promotion boundary before the owner decision

At this stage P1.5 remained open because the current-source candidate did not
pass every predeclared JavaFX control. The follow-up preserved exact sample/ARGB
checks, the retained-Julia first-tile target, cold-startup scope and sustained navigation/memory/thermal
scope for a future production candidate. The original retained cache remains
unchanged. Windows/Linux graphical behavior, separately gated fullscreen cases
and physical scanout were not verified by this local run.

## Predeclared follow-up gate

The next candidate may start the first retained AA tile after coloring only
that tile's cached pixels, then color the remaining cached pixels before
dispatching other tiles. The original path is frozen as build A; a candidate
will be frozen as build B only after exact-frame regressions pass. Both builds
must use the same JDK, dependencies, fixture matrix and JavaFX driver. The
[headless policy](policies/p1-5-aa-retained-headless.json) and
[JavaFX policy](policies/p1-5-aa-retained-fx.json) each require 30 alternating
fresh process pairs, three warmups and one measured repetition per process.
Every declared group must pass its 95% paired-ratio bound: retained-Julia first
tile B/A at most 0.90; retained full AA and publication, fresh AA controls and
base-only publication B/A at most 1.05. Both Fast and Refined are required in
JavaFX. Exact samples and ARGB pixels must match, with no stale publication.

The cold fixture in each fresh JVM must also complete and match exact controls.
Report its first/full publication and process startup observations separately;
it cannot be substituted for a measured process-launch-to-window or physical
scanout latency. A candidate that misses any declared gate is rejected and
removed. A passing paired gate must then repeat portable, JavaFX/native and
sustained checks on the final source before P1.5 can close.

## First candidate and predeclared replication

Build A (`78128cad55c3ccd15d8183ed0b45baa1ed7591446fd92d1b9b984ba5a90c17d6`)
and the first candidate B (`b6fe209a4448dd99a815c445a0671e1d7dfdde7a7fd3a5b62944fbfbeb638d04`)
completed the 30-pair headless (private archive: `p1-5-aa-headless-pairs-20260924/analysis.json`)
and JavaFX (private archive: `p1-5-aa-fx-pairs-20260925/analysis.json`) campaigns. Both exact
campaigns were timing-inconclusive. Retained Julia first-AA-tile ratios passed
the 0.90 target in headless (0.722), Fast (0.791), and Refined (0.679). The
headless fresh Mandelbrot first tile and several JavaFX controls exceeded the
1.05 upper confidence bound. This candidate is not promoted.

Candidate B2 (`c3d9b4ea9b067fc47a96f1e8e60a1ded872022d6e6cb2d0a5bb10a1ea871d9ab`)
restores the original preparation order for frames without retained AA samples.
The retained first-tile change remains. Before measuring B2, the
[headless](policies/p1-5-aa-retained-b2-headless.json) and
[JavaFX](policies/p1-5-aa-retained-b2-fx.json) replication policies fix the
same fixtures, modes, metrics and ratio limits at 60 alternating fresh process
pairs, three warmups and three measured repetitions per process. All declared
groups must pass. An inconclusive group does not authorize promotion. Exact
sample/ARGB controls, cold-fixture completion and the later portable, native,
JavaFX and sustained checks remain mandatory.

The separate cold-startup comparison was predeclared to run the same frozen A/B production
classes with one shared `ColdStartupBenchmark` test class, fresh process/home/
JavaFX cache per launch, and 30 alternating AB/BA process pairs. It measures
host process launch to `Stage.show()` completion and the first JavaFX layout
pulse in the actual application. Both paired median ratios must have 95%
bootstrap upper bounds at most 1.05. These markers end before physical
scanout and do not measure the first completed fractal frame. The probe class,
selected JDK and frozen build hashes are recorded with the campaign.

The B2 headless replication (private archive: `p1-5-aa-b2-headless-pairs-20260925/analysis.json`)
completed all 60 process pairs with 180 measured sequences per build and exact
fingerprints. All seven declared timing groups passed. Retained-Julia first AA
tile B/A was 0.665 (95% interval 0.647–0.687); full AA was 1.013
(0.999–1.036) and total operation was 1.001 (0.997–1.005). Fresh Julia and
Mandelbrot first-tile and operation controls all stayed within 1.05. This
headless result alone did not promote B2. The 60-pair
JavaFX replication (private archive: `p1-5-aa-b2-fx-pairs-20260925/analysis.json`) was
timing-inconclusive: retained first-AA-tile B/A passed in Fast (0.797) and
Refined (0.650), but full AA duration in both modes and fresh Julia Fast
first-AA-tile did not meet the 1.05 upper confidence bound. All exact controls
passed. B2 is not promoted.

Candidate C (`56ee2aa7540ce3b9a6efa141e5a35d6d64c02d2acf61ebef94be9f312d01b8b2`)
keeps the first retained tile's colors in a separate array, then completes the
full cached recolor through the original parallel pass. The first tile reads
only its prepared array while the other tiles wait for the full array, so the
overlapping work has no shared-array write/read race. This targets B2's full-AA
cost. Before measuring C, the [headless](policies/p1-5-aa-retained-c-headless.json)
and [JavaFX](policies/p1-5-aa-retained-c-fx.json) policies fix the same 60
alternating pairs, three warmups, three measured repetitions, fixtures,
metrics and limits as B2. Every group must pass; C also needs the separate
cold-startup and final correctness/sustained gates before promotion.

The candidate C headless campaign (private archive: `p1-5-aa-c-headless-pairs-20260925/analysis.json`)
completed all 60 pairs with exact fingerprints and passed all seven groups.
Retained Julia first-AA-tile B/A was 0.679 (95% interval 0.664–0.705), full
AA was 1.016 (1.005–1.029), and total operation was 1.003 (0.998–1.008).
The JavaFX comparison (private archive: `p1-5-aa-c-fx-pairs-20260925/analysis.json`) was
timing-inconclusive despite exact controls and first-AA-tile ratios of 0.650
in Fast and 0.649 in Refined. Full AA and four short fresh/base controls had
upper intervals above 1.05. C is not promoted.

Candidate D (`0a925ff54ae1f9695f95e63d97f4b1f2b3da238e0954b4a7912f7226eb685143`)
stores the first tile in a tile-sized buffer rather than allocating a second
full-frame color array. The first tile still reads a separate immutable buffer,
and the original full parallel recolor runs before other tiles are submitted.
The scoped cache and JavaFX benchmark tests passed before freezing D. Its
[headless](policies/p1-5-aa-retained-d-headless.json) and
[JavaFX](policies/p1-5-aa-retained-d-fx.json) policies were copied before
measurement from C's 60-pair, three-sample policies without changing any
fixture, metric or limit. All declared timing, exactness and later cold,
portable, native and sustained gates remain required.

The candidate D headless campaign (private archive: `p1-5-aa-d-headless-pairs-20260925/analysis.json`)
completed all 60 pairs with exact fingerprints and passed all seven timing
groups. Retained Julia first-AA-tile B/A was 0.670 (95% interval
0.655–0.695), full AA was 1.013 (0.996–1.030), and total operation was
1.003 (0.999–1.007). This does not replace the JavaFX decision.

The first candidate D JavaFX campaign (private archive: `p1-5-aa-d-fx-pairs-20260925/analysis.json`)
completed with exact controls but was timing-inconclusive: 14 of 18 groups
passed, including retained first-AA-tile B/A of 0.653 in Fast and 0.643 in
Refined. Retained Fast AA duration, fresh Julia Fast first AA tile and two
base-only first-publication controls had upper intervals above 1.05. The
cold-startup campaign (private archive: `p1-5-aa-d-cold-startup-20260925/summary.json`) passed
both declared 30-pair limits: Stage.show B/A 0.997 (upper 1.004) and first
layout pulse 0.998 (upper 1.005). The shared probe class and JDK/build hashes
are captured in that campaign's definition.

Before any further D timing, the final
[JavaFX confirmation policy](policies/p1-5-aa-retained-d-confirm-fx.json)
fixes 120 alternating fresh process pairs, three warmups and five measured
repetitions per process. It retains every fixture, mode, metric and threshold
from the prior D JavaFX policy. All 18 groups must pass. If any group remains
inconclusive or fails, reject D rather than changing this gate again.

The first confirmation launch stopped at `pair-48-B` after macOS entered Deep
Idle during `pair-47-A`. The failed-process log (private archive: `p1-5-aa-d-confirm-fx-pairs-20260925/pair-48-B.log`)
shows a missing post-layout pulse after exact rendering had completed; the
macOS power evidence (private archive: `p1-5-aa-d-confirm-fx-pairs-20260925/sleep-evidence.txt`)
records a Deep Idle wake at 10:02 local time. The incomplete campaign is
preserved and is not a timing verdict. The same frozen builds and unchanged
policy were rerun from the beginning under a `caffeinate -dims` display/system
sleep assertion; `pmset -g assertions` confirmed both assertions were active.

The awake 120-pair confirmation (private archive: `p1-5-aa-d-confirm-fx-awake-20260925/analysis.json`)
completed with exact controls but was **timing-inconclusive**: 16 of 18 groups
passed. Retained Julia first-AA-tile B/A was 0.653 in Fast and 0.610 in
Refined, while retained full AA and full publication passed both modes. Fresh
Julia Refined first-AA-tile had B/A 1.014 (95% interval 0.985–1.066), and
fresh Mandelbrot Fast first-AA-tile had B/A 1.012 (0.921–1.083). Both upper
bounds exceed the predeclared 1.05 control limit. D therefore fails the joint
promotion gate and its production/test-only cache changes were removed. The
30-pair cold-startup result remains a valid observation for D, not a promotion.
Portable and native/FX regression tests were run on the original retained
source. A candidate-D sustained navigation/memory/thermal run was not started
after the paired timing gate failed. No candidate-D sustained claim is made.

## Final source and verification state

Candidate D's production and cache-test edits were removed, leaving the
original retained AA implementation. On that final source, `mvn test` passed:
447 reported, 393 executed and 54 opt-in skips. The active-desktop
`FractalResizeFxTest,BaselineFxBenchmarkTest` run passed 31 executed cases with
two separately gated fullscreen skips. The four baseline-summary Python test
modules passed 39 tests, and `git diff --check` passed.

The initial current-source native menu/material run above passed before the
candidate experiments. A final active-desktop
`MacContextMenuFxTest,MacLoadingMaterialFxTest` run failed to establish a native
menu session in two menu cases; the material case passed and one case was
skipped. A focused `MacContextMenuFxTest` retry failed earlier because the test
window did not obtain desktop focus (`stage.isFocused()` was false before the
menu request). These final native reruns do not establish a menu regression in
the unchanged production source, but they also do not verify native menu
behavior in the final desktop state. The final native check is therefore
unverified. Windows and Linux graphical behavior and physical scanout remain
unverified by this macOS campaign.

## Candidate E: predeclared decision before source edits

Candidate E will prepare only the first retained tile directly in the
frame-sized cache-color array, submit that tile, then recolor cached pixels
outside the tile before submitting the remaining tiles. The first tile and the
background recolor write disjoint array positions. Unlike D, no second color
buffer or duplicate first-tile coloring is planned. Empty-cache processing
must keep the control path. The frozen control is build A with identity
`78128cad55c3ccd15d8183ed0b45baa1ed7591446fd92d1b9b984ba5a90c17d6`.

Before editing, the [headless policy](policies/p1-5-aa-retained-e-headless.json),
[JavaFX screening policy](policies/p1-5-aa-retained-e-fx-screen.json), and
[JavaFX confirmation policy](policies/p1-5-aa-retained-e-fx-confirm.json)
fix the same fixtures, modes, metrics and B/A limits as D: retained Julia
first AA tile at most 0.90, every full-AA, publication, fresh-AA and base-only
control at most 1.05, using the 95% paired-ratio upper bound. Headless and
JavaFX screening use 60 alternating process pairs with three measured samples
per process; confirmation uses 240 pairs with ten samples. All runs use three
warmups, a cold fixture, exact sample/ARGB controls and frozen matching driver
classes. A confirmed timing failure at screening rejects E. Screening may
advance to confirmation with inconclusive controls only if the retained first
tile and full AA/publication groups pass. Every declared confirmation group
must pass; an inconclusive group rejects E. The confirmation policy will not
be changed after examining candidate timing.

Only a passing paired decision permits the separate 30-pair cold-startup gate,
portable and active-desktop JavaFX/native regressions, and a final-source
sustained navigation/memory/thermal run. Cancellation, palette, cache reuse and
precise deep AA must remain exact. A rejected candidate will be removed.

The frozen E build identity was
`3b276d6f644a4b0c97c23a5cfc9ae77595276750e610942229fe406f541e7ee7`.
Its 60-pair headless screen (private archive: `p1-5-aa-e-headless-pairs-20260927/analysis.json`)
completed with exact sample/ARGB controls. Retained Julia first AA tile passed
at B/A 0.665 (95% interval 0.640–0.706); five other groups passed. Retained
full AA was inconclusive at B/A 1.020 (0.997–1.061), crossing the 1.05 control
limit. The predeclared screening rule therefore stops E before JavaFX. E's
production and cache-test changes were removed. The JavaFX screening and
confirmation policies remain unused, and no E cold-startup or sustained claim
is made.

## Candidate F: predeclared final first-tile experiment

E added a per-entry mask check to the full parallel recolor and did not prove
the full-AA limit. F returns to D's unchanged full-snapshot recolor after
submitting the first tile, but removes D's frame-sized selected-pixel mask.
The immutable snapshot will test the selected tile's coordinates directly
while building its membership mask and packed first-tile colors. This is a
retained-only preparation change; empty-cache processing stays at the control
path. The frozen A build and exact-output requirements remain the same.

Before F source edits, the [headless](policies/p1-5-aa-retained-f-headless.json),
[JavaFX screen](policies/p1-5-aa-retained-f-fx-screen.json), and
[JavaFX confirmation](policies/p1-5-aa-retained-f-fx-confirm.json) policies
fix all D fixture, mode, metric and limit definitions. Headless and JavaFX
screening use 60 alternating process pairs and three samples per process;
confirmation uses 240 pairs and ten samples. The retained first tile and
retained full AA/publication groups must pass screening to proceed. A
confirmed regression rejects F immediately; an inconclusive non-target
control can advance only to the one predeclared confirmation. Every final
group must pass its 95% paired-ratio upper limit; an inconclusive final group
rejects F. No additional threshold or campaign-size changes are planned.
Only a passing F decision triggers the 30-pair cold-startup, portable,
active-desktop JavaFX/native and final-source sustained gates.

Build F identity is
`a067e3fbf8161b50a79456e935d792f7aec562da82fbcad1ea6c51fdfb447f3e`.
The 60-pair headless campaign (private archive: `p1-5-aa-f-headless-pairs-20260927/analysis.json`)
completed with exact sample/ARGB controls and passed all seven timing groups.
Retained Julia first AA tile was B/A 0.636 (95% interval 0.623–0.654), full AA
was 1.002 (0.984–1.017), and total operation was 0.997 (0.993–1.004).
This permits the predeclared JavaFX screen; it does not promote F.

The 60-pair JavaFX screen (private archive: `p1-5-aa-f-fx-screen-pairs-20260927/analysis.json`)
completed with exact sample/ARGB controls but was timing-inconclusive. Retained
Julia first AA tile passed at B/A 0.614 in Fast and 0.616 in Refined. Retained
full publication passed in both modes, but retained full AA remained
inconclusive: Fast B/A 1.021 (95% interval 0.989–1.053) and Refined 1.017
(0.997–1.055), both crossing the declared 1.05 upper limit. Several fresh-AA
controls were also inconclusive. F therefore cannot advance to the
predeclared 240-pair confirmation. Its production and cache-test changes were
removed. The confirmation policy remains unused, and no F cold-startup or
sustained claim is made. P1.5 remains open.

After restoring the original retained source, `mvn test` passed with 447
reported tests, zero failures/errors and 54 opt-in skips. The four Python
baseline-summary test modules passed 39 tests, and `git diff --check` passed.
No production AA or cache-test edits from E or F remain. The earlier macOS
JavaFX/native findings remain scoped as described above; neither rejected
candidate reached final-source graphical or sustained acceptance.

## Candidate G: predeclared completed-pass decision reuse

F's saved JavaFX rows show about 201 ms for the untimed first AA preparation
and 37 ms for the measured same-frame Julia AA call. The early-tile overlap
improved the first tile but did not establish the full-AA control. G instead
will record pixels found not to need supersampling in a completed AA pass and
skip their candidate test in a subsequent pass on the same completed frame,
same immutable smooth coloring, sampling pattern and deep/regular mode.
Worker-owned positions in a byte array avoid a shared write lock. Decisions
will be published only after all worker tasks finish and the generation is
still current; cancellation, frame reuse, color or pattern changes must not
reuse them. Cached candidate samples retain their existing bounded cache and
exact fallback behavior. The extra frame-sized decision array and fresh-pass
write cost require memory and control checks.

Before source edits, the [headless](policies/p1-5-aa-retained-g-headless.json),
[JavaFX screen](policies/p1-5-aa-retained-g-fx-screen.json), and
[JavaFX confirmation](policies/p1-5-aa-retained-g-fx-confirm.json) policies
fix the same seven headless and 18 JavaFX metric groups, fixture matrix, modes,
and B/A limits as F. Headless and JavaFX screens use 60 alternating fresh
process pairs with three measured samples; the one confirmation uses 240 pairs
with ten samples. A screen with any confirmed timing failure rejects G. A
passing retained first-tile target with inconclusive controls may advance to
the one confirmation; this is declared before seeing G results. Every final
group must pass its 95% paired-ratio upper limit: at most 0.90 for retained
Julia first tile and 1.05 for all controls. Exact sample/ARGB controls are
mandatory throughout. The frozen A build remains unchanged. Only a passing
joint decision triggers 30-pair cold startup, portable and active-desktop
JavaFX/native tests, and a final-source sustained navigation/memory/thermal
run. A rejected or inconclusive G will be removed.

The frozen G build was
`5145d5aefafa41db4dcbdac1cbf72e7db2d086f14ef60b26c819ce6e98f3fabe`.
The 60-pair headless screen (private archive: `p1-5-aa-g-headless-pairs-20260927/analysis.json`)
completed on the same AC-powered M3 Pro and JDK 26.0.2. The preflight and
all campaign processes exited successfully, including exact benchmark output
checks. Retained Julia first AA tile failed its declared 0.90 B/A upper bound:
median paired ratio **1.051**, 95% interval **[0.994, 1.174]**. Retained full
AA passed at **0.529** [0.482, 0.553], and retained full operation passed at
**0.950** [0.943, 0.961]. The other three headless controls passed; the fresh
Mandelbrot first-tile control was inconclusive at **1.123** [1.045, 1.330]
against its 1.05 upper bound. The confirmed target failure rejects G under
its predeclared screening rule. Its production and regression-test changes
were removed. JavaFX confirmation, cold startup, final-source sustained
diagnostics and native retests were not run for G; no promotion claim follows.

Candidate exploration is closed after seven attempted designs. The original
retained AA cache remains in production with its measured Julia first-tile
tradeoff. Further parameter tuning of the rejected candidates is not part of
this decision.

## First-tile exception and outstanding paired gate — 2026-09-27

The owner explicitly chose to retain the original CPU AA optimization and
accept its measured retained-Julia first-tile cost of about 5 ms relative to
the pre-`3454dc7` implementation. This amends the first-tile promotion
criterion for that existing optimization only. It does not waive exact sample
or ARGB correctness, other timing controls, or future optimization gates. The
original [paired AA result](CPU_AA_OPTIMIZATION_RESULTS.md#correctness-and-remaining-work)
measured 30 frames per build/control in each of three alternating fresh JVM
pairs: retained Julia full AA improved from 280.18 to 25.91 ms, while first AA
tile changed from 6.38 to 11.10 ms. The original cache remains the final
production implementation; no candidate B through G is retained.

Final source checks after removing G:

| Check | Result | Scope |
| --- | --- | --- |
| `mvn test` | 447 reported, 393 executed, 54 opt-in skips; zero failures/errors | Portable CPU, precision, palette/AA, fallback, cache/reuse and cancellation. |
| `mvn -Dfractal.fx.tests=true -Djavafx.cachedir=/private/tmp/fractallens-p1-5-final-fx-cache -DreuseForks=false -Dtest=FractalResizeFxTest,BaselineFxBenchmarkTest test` | 33 reported, 31 executed, 2 separately gated fullscreen skips; zero failures/errors | Active-desktop JavaFX resize, pan, AA publication, cancellation and exact fixture controls. |
| `mvn -Dfractal.fx.tests=true -Djavafx.cachedir=/private/tmp/fractallens-p1-5-final-native-fx-cache -DreuseForks=false -Dtest=MacContextMenuFxTest,MacLoadingMaterialFxTest test` | 4 reported, 3 executed, 1 separately gated fullscreen/multi-display skip; zero failures/errors | Active-desktop native macOS menu/material lifecycle; resolves the earlier focus-limited rerun. |
| `python3 -m unittest discover -s scripts -p 'test_summarize_baseline_*.py'` | 39 passed | Benchmark evidence parsers. |
| `git diff --check` | Passed | Repository whitespace hygiene. |

The [current-source 652-second diagnostic](p1-5-aa-sustained-20260924/summary.json)
is still applicable to the final production code: the G source changes were
removed and the production AA files match the original retained
implementation. It passed exact sample/ARGB controls for 108 navigation
trials, plus the bounded memory/GC/thermal observations described above.
The frozen-control rows in the later headless and JavaFX pair campaigns and
the A side of the 30-pair
cold-startup campaign (private archive: `p1-5-aa-d-cold-startup-20260925/summary.json`)
also exercise that same implementation; candidate-side results are not used
to claim a new improvement. The final native rerun passed in an active desktop
session. Fullscreen/multi-display cases were skipped by their separate gates;
Windows/Linux graphical behavior and physical scanout were not checked here.
The CPU-only AA change does not require a native GPU device-loss run.

The paired production-service/JavaFX and cold-startup rows above compare the
current optimized implementation with rejected first-tile candidates. The
before/after result from 2026-09-05–06 used historical builds. Neither is a
current-source before/after comparison of the retained `3454dc7` optimization.
P1.5 therefore remained open while that 9.9 gate was measured and assessed.

## Predeclared current-source retained-AA comparison

Build A will use the current source tree with only the production AA changes
from `3454dc7` reversed in `InteractiveAntialiasService` and
`AntialiasSampleCache`. The later `PreciseFractalSampler` Julia/deep-zoom fix
and every other current production source, dependency, fixture and benchmark
driver remain identical. Build B is the frozen current-source optimized AA
build. The control service after reversal is compared against the
pre-`3454dc7` service; its only difference is the later precise-sampler API.
The compiled build manifests and source hashes must verify this relationship
before any timing decision.

The [headless policy](policies/p1-5-retained-vs-pre-headless.json) and
[JavaFX policy](policies/p1-5-retained-vs-pre-fx.json) were fixed before timing.
Each has 60 alternating fresh-process pairs, three warmups and three measured
samples per process on the same 1512x982 fixtures and selected JDK. Julia
retained, fresh Julia, and fresh Mandelbrot full-AA metrics must have a 95%
paired-ratio upper bound at most 0.90 for B/A. Fresh-AA first-tile, complete
operation and base-only JavaFX publication controls must have an upper bound
at most 1.05. Fast and Refined JavaFX modes are both required. Retained-Julia
first AA tile is still measured and reported from the paired rows, but is
excluded from the pass/fail policy under the owner's explicit exception. Its
exclusion does not apply to fresh AA first tiles. All non-cancelled A/B sample
and ARGB fingerprints must match; the existing sample-level regression and
sustained exact-frame checks remain required.

A separate active-desktop 30-pair process-launch-to-Stage.show/first-layout
comparison must have a 95% paired-ratio upper bound at most 1.05 for both
metrics. Physical scanout is outside its scope. Any failed or inconclusive
required group keeps P1.5 open; neither fixture selection nor timing limits
will be changed after observing the result.

The frozen A control identity is
`f9280359f7d7ce943dac3686393a0485080719a4795f1116f3cb4b2d22fd86f7`;
the unchanged B identity is
`78128cad55c3ccd15d8183ed0b45baa1ed7591446fd92d1b9b984ba5a90c17d6`.
Their source manifests differ only for the two production AA files. The A
service matches the pre-`3454dc7` source except for the later
`PreciseFractalSampler` replacement; all benchmark test classes, dependencies
and the native menu binary are shared byte for byte. A preliminary one-sample
run matched all nine A/B fixture-phase sample and ARGB fingerprints. This is
an exact-output smoke check, not the paired timing verdict.

The [60-pair headless campaign](p1-5-retained-vs-pre-headless-20260927/analysis.json)
completed with matched A/B fingerprints and passed all eight declared groups.
Retained Julia full AA B/A was **0.092** (95% interval **0.088–0.095**);
fresh Julia full AA was **0.452** (0.447–0.456), and fresh Mandelbrot full AA
was **0.184** (0.181–0.187). The retained-Julia first-tile diagnostic had a
median process-paired B/A ratio of **1.711** and is excluded from the verdict
only by the accepted exception. This headless result alone is not P1.5
promotion; the JavaFX and cold-startup comparisons remain required.

The first JavaFX campaign
`p1-5-retained-vs-pre-fx-20260927` (private archive: `p1-5-retained-vs-pre-fx-20260927/`)
was stopped during pair 04-B after that JVM had written exact results and a
`SUCCESS` marker but did not exit. The saved
thread dump (private archive: `p1-5-retained-vs-pre-fx-20260927/pair-04-B-threads.txt`)
shows the main thread waiting in `Platform.exit()` while the JavaFX thread
remained in native `MacTimer._stop`; the runner therefore had no completed
pair result. AC power and `caffeinate -dims` display/system assertions were
active. This incomplete campaign is an environment/runtime shutdown failure,
not a timing verdict. The fresh run reused the unchanged frozen builds,
policy, selected JDK and active-desktop conditions.

The [fresh 60-pair JavaFX campaign](p1-5-retained-vs-pre-fx-retry-20260927/analysis.json)
completed in the active desktop session. All 120 JVMs exited successfully,
every before/after power observation reported AC, and all thermal observations
reported `nominal`. Exact sample/ARGB fingerprints matched. All 16 declared
AA metric groups passed, including retained Julia full-AA B/A of 0.156 in
Fast and 0.154 in Refined, and retained full-publication B/A of 0.384 and
0.378 respectively. Three of four base-only JavaFX controls were
timing-inconclusive against the predeclared 1.05 upper bound: Fast first
publication 1.013 [0.976, 1.067], Fast full publication 1.020 [0.984, 1.088],
and Refined first publication 1.023 [0.978, 1.089]. Refined full publication
passed at 1.008 [0.986, 1.047]. The overall JavaFX timing verdict is
**inconclusive**; this does not establish the complete 9.9 paired gate.

The laptop changed to battery power after the completed AC-powered headless
and JavaFX campaigns. A separate
battery cold-startup attempt (private archive: `p1-5-retained-vs-pre-cold-battery-20260928/CAMPAIGN_INTERRUPTED`)
was stopped at the owner's request after 13 complete A/B pairs. It has no
30-pair summary and no timing verdict. At that point, the cold-startup gate and
the three inconclusive JavaFX base-only controls remained open for AC checks.
No battery observations were pooled with the AC timing campaigns.

The fresh [30-pair AC cold-startup campaign](p1-5-retained-vs-pre-cold-ac-20260928/summary.json)
passed both declared 1.05 limits: launch-to-`Stage.show` B/A 0.993
(95% interval 0.968–1.034) and launch-to-first-layout B/A 0.993
(0.960–1.042). All 60 before/after observations reported AC power and
charging, from 65% at the first launch to 67% at the last. This is software
window/layout timing, not physical scanout. At that point, the three
inconclusive base-only JavaFX controls were the sole open paired timing groups.

## Predeclared one-time base-publication confirmation

The first complete JavaFX before/after campaign already established all 16 AA
groups but left three base-only groups inconclusive. Before any additional
timing, a single [confirmation policy](policies/p1-5-retained-vs-pre-fx-base-confirm.json)
fixes 240 alternating fresh process pairs, three warmups and three measured
samples per process on the same 1512x982 Mandelbrot overview and regular-AA
fixtures in Fast and Refined modes. Both frozen build identities, selected JDK,
JavaFX pipeline, fixture definitions and 1.05 base-publication limits are
unchanged. All four overview first/full publication groups, including the one
that already passed, must pass their 95% paired-ratio upper bounds. Regular-AA
full work and publication remain declared 0.90 targets to check that the same
AA path and exact output are exercised in the confirmation. The accepted Julia
first-tile exception is unrelated to this policy. The confirmation stands
alone; it will not pool battery observations, relax limits or selectively
discard measured pairs. A failed or inconclusive group leaves P1.5 open.

## Final current-source decision

The [240-pair AC JavaFX confirmation](p1-5-retained-vs-pre-fx-base-confirm-20260928/analysis.json)
passed all eight predeclared groups. The four base-only publication controls
had B/A median paired ratios and 95% intervals of **0.987 [0.954, 1.028]**
(Fast first visible), **1.000 [0.979, 1.029]** (Fast full),
**0.974 [0.936, 1.015]** (Refined first visible), and
**0.996 [0.973, 1.019]** (Refined full). Every upper bound is below the
predeclared 1.05 limit. The four regular-AA work/publication targets also
passed their 0.90 limits: Fast AA **0.283 [0.277, 0.289]**, Fast full
**0.338 [0.333, 0.347]**, Refined AA **0.274 [0.271, 0.278]**, and
Refined full **0.323 [0.318, 0.326]**. All 480 fresh JVMs exited with code
zero; every before/after observation recorded AC power and `nominal` thermal
state. All compared non-cancelled sample and ARGB fingerprints matched. The
launch records (private archive: `p1-5-retained-vs-pre-fx-base-confirm-20260928/launches.json`),
[environment](p1-5-retained-vs-pre-fx-base-confirm-20260928/environment.json),
and frozen [A](p1-5-retained-vs-pre-fx-base-confirm-20260928/build-A.json)/[B](p1-5-retained-vs-pre-fx-base-confirm-20260928/build-B.json)
build manifests preserve the provenance. The saved policy is byte-identical
to the predeclared policy linked above.

The current-source [headless comparison](p1-5-retained-vs-pre-headless-20260927/analysis.json),
[JavaFX comparison](p1-5-retained-vs-pre-fx-retry-20260927/analysis.json),
[base-publication confirmation](p1-5-retained-vs-pre-fx-base-confirm-20260928/analysis.json),
and [AC cold-startup comparison](p1-5-retained-vs-pre-cold-ac-20260928/summary.json)
jointly establish the 9.9 before/after timing gate for the retained AA code.
The prior exact-frame, portable/macOS JavaFX/native, and sustained
navigation/memory/GC/thermal checks apply to the same retained production
source. Retain the original CPU AA optimization with the owner's scoped
retained-Julia first-tile exception; reject candidate B through G. No
candidate change is promoted. The first-tile optimization opportunity
remains open in 9.2 and requires its own acceptance evidence.
