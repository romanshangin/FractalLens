# macOS Spaces presentation validation

This is a correctness check for the ES2/Metal comparison, separate from the
[timing campaigns](PRISM_PIPELINE_BENCHMARK.md). The symptom is a completed
render that remains invisible after a Space switch or in Mission Control until
another pan causes a repaint. A software completion event alone is not a pass.

## Driver and scope

[`SpaceSwitchProbe`](../src/test/java/com/shangin/fractal/ui/SpaceSwitchProbe.java)
opens the production `MainView`, menus, controller, surface and AA path. It uses
an isolated session file and the direct-double CPU backend for the standard
Mandelbrot overview. Do not navigate into deep zoom in this driver. It neither
captures the scene graph nor adds animated markers or requests repaints.

F8 focuses the canvas, arms a calculation gate and sends a controlled 120-point
drag through the installed production mouse handlers. Successive trials drag
right, left, down and up. F7 arms the same gate without generating input, for
real pointer or arrow-key navigation. Calculation waits on a worker thread until three seconds after the
window loses focus. The gate also applies to replacement requests within that
trial. Cancellation interrupts the wait, and close releases it and closes the
backend. Missing focus loss times out the render after 30 seconds; this is an
invalid trial, not evidence of the presentation defect. The gate changes timing
deliberately and must never be used as a performance measurement.

The log records `waiting`, `focus=false`, `release`, `frame_promoted`, and
`complete`, including the viewport and physical render dimensions. Focus loss
does not prove a Space switch: the operator must independently observe that
another Space or Mission Control is displayed. A valid offscreen trial needs
the new request to reach `complete` while `focused=false`, before returning.

F9 records a read-only model checkpoint. Use it only after recording the unaided
visual verdict. Keyboard input, activating the app, opening a menu, snapshots,
export and pointer movement over the canvas must not precede that verdict.

## Build and launch

Use the same compiled sources and dependencies for both pipelines. In the
repository root, build with the checked-in Maven Wrapper:

```shell
./mvnw -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/space-probe-classpath.txt
```

Run the following in an active graphical macOS session. `JAVA_HOME` must be the
JDK used for compilation; use a fresh private `OUTPUT` directory. Raw logs and
desktop captures remain private. Set `PIPELINE=es2`, then repeat with `mtl`,
using different session files and caches. Do not rebuild between those runs.

```shell
PIPELINE=es2
OUTPUT="${TMPDIR}/fractallens-spaces-new-run"
mkdir -p "$OUTPUT"
scripts/macos/macos-java "--fractal-java-home=$JAVA_HOME" \
  --module-path "target/classes:$(cat target/space-probe-classpath.txt)" \
  --patch-module com.shangin.fractal=target/test-classes \
  --enable-native-access=javafx.graphics,org.lwjgl,com.shangin.fractal \
  --add-exports=javafx.graphics/com.sun.glass.ui=com.shangin.fractal \
  --add-opens=javafx.graphics/com.sun.glass.ui.mac=com.shangin.fractal \
  --add-exports=javafx.graphics/com.sun.javafx.stage=com.shangin.fractal \
  --add-exports=javafx.graphics/com.sun.javafx.tk=com.shangin.fractal \
  -Dfractal.gpu.enabled=false -Dfractal.gpu.mandelbrot.enabled=false \
  -Dprism.verbose=true "-Dprism.order=$PIPELINE" \
  "-Djavafx.cachedir=$OUTPUT/cache-$PIPELINE" \
  -m com.shangin.fractal/com.shangin.fractal.ui.SpaceSwitchProbe \
  "$OUTPUT/session-$PIPELINE.json" > "$OUTPUT/$PIPELINE.log" 2>&1
```

Check that each log initializes exactly the requested `ES2Pipeline` or
`MTLPipeline`. Remove inherited JVM environment overrides for the campaign;
do not infer the active pipeline from the requested property alone.

## Trial matrix and verdicts

Start each process at the default Mandelbrot overview, zoom in about 1.5–3
times with the wheel or a pinch, and wait for that frame to complete. Pan keeps
the view inside the default overview, so at the default view itself F7 or F8
starts no render and logs no `waiting`. The controlled 120-point drag in the
1200x760 window needs at least about 1.35 times zoom for the vertical
directions. Stay at this shallow zoom; the four F8 directions return to the same
center. For each pipeline, exercise pan left, right, up and down in each of
these two sequences:

1. Press F8 for a controlled drag (or F7 followed by an actual pan), immediately
   switch to an adjacent Space, wait for the
   logged offscreen completion, then return using the system Space shortcut.
   Inspect the newly exposed region without further application input.
2. Start another controlled or manual pan, switch away and wait for offscreen completion. Enter Mission
   Control and inspect the original Space's thumbnail without selecting it.
   Record this verdict separately, then return and record the window verdict.

Use actual drag gestures as well as the installed arrow-key navigation. Do not
count a completion before focus loss, a failed Space switch, an unreadable
thumbnail, or an app-window capture that does not show the compositor's visible
output as a successful trial. If thumbnails are collapsed, expose them using
Mission Control controls, without activating the original Space.

Also repeat natural, ungated pan/switch attempts in a fresh process without
pressing F7 or F8. These test real gesture timing; label a trial inconclusive when
completion occurred before leaving. Keep controlled and natural counts separate.
Mode, gesture, direction, logical window size, output scale, pipeline, completion
ordering, visible outcome and recovery action belong in the trial record.

A visible failure is stale, missing, banded or mixed-frame fractal content after
confirmed completion. Only after recording it, try a small pan and record
whether it makes the completed content visible. An accessibility status badge
or buffer fingerprint does not prove that the fractal is displayed.

Report pipeline-specific failures over valid trials and all invalid/unobserved
trials. No failures in a finite run is bounded evidence, not proof of universal
correctness. If ES2 never reproduces the symptom, the run does not establish a
regression comparison even if Metal also shows no failures. Completing this
check does not waive the remaining FX, appearance, fallback, packaging and
platform checks in the pipeline protocol.

## Execution record

2026-10-28 preparation on Apple M3 Pro, macOS 27.2, JDK 26.0.1 and JavaFX
26.0.2, branch `chore/prism-pipeline-benchmark` based on `bcbfca4` with the new
driver uncommitted:

- Two early ES2 driver launches initialized `ES2Pipeline` and displayed a
  completed default Mandelbrot window at 1200x760 logical points, scale 2.
- The initial keyboard attempts did not produce a new render, even after the
  canvas focus was confirmed. Native pointer automation then returned
  `noWindowsAvailable`. These attempts are invalid trials, not presentation
  failures or successful acceptance checks.
- Desktop launch access subsequently became unavailable. The final driver adds
  the controlled F8 drag so that the next run does not depend on that pointer
  automation. It still requires an independently observed system Space switch.
- Valid offscreen-completion trials: ES2 **0**, Metal **0**. Return-to-Space and
  Mission Control thumbnail acceptance are **pending**. No comparison of
  failure rates or conclusion that Metal fixes the defect is established by
  these preparation runs.

The driver gate has portable tests covering held calculation until scheduled
release, completed pixel contents, cancellation without publication and
idempotent close while calculation is held. They do not replace the visible
desktop matrix. Raw launch logs remain private; the final controlled-drag
driver has not yet completed a desktop acceptance trial.

Developer checks on the final driver passed: all three `SpaceSwitchProbeTest`
tests; the full portable suite with an isolated JavaFX cache (481 executed,
130 opt-in skips, no failures/errors); all 14 publication-script tests;
`check_public_data.py`; secret scans of history and the candidate tree; and
`git diff --check`. The skipped FX/native tests are not desktop acceptance.

2026-10-28 desktop run, ES2, Apple M3 Pro, macOS 27.2, JDK 26.0.1 and JavaFX
26.0.2; the log confirmed `ES2Pipeline`:

- Controlled trials 1 and 2 (F8, right and left) started at the default
  overview. Each logged `armed`, `controlled_drag`, focus loss and `release`,
  but no `waiting`, `frame_promoted` or `complete`: pan was clamped at the
  default view, so no render started. Both are invalid trials, not presentation
  results. The start instruction above now requires a shallow zoom first.
- Shallow zoom (scale about 0.0425) before the next trials; all renders used
  the 2400x1520 physical target, 1200x760 logical points at scale 2.
- Controlled trials 3 and 4 (F8, down and up) are valid offscreen trials:
  `waiting` with focus, focus loss, `release`, then `frame_promoted` and
  `complete` with `focused=false`; focus returned about 30 s and 23 s after
  completion. The unaided window verdicts after returning were not recorded,
  so both trials are unobserved, not passes. Trial 4 was followed about 6 s
  after returning by a manual drag.
- That manual drag was a natural, ungated attempt in the same process, not a
  fresh one. It reached `complete` with focus and lost focus about 0.24 s
  later, so it is inconclusive as an offscreen trial; whether the operator
  switched Spaces or only activated another application was not confirmed.
  After focus returned, the window showed a mixed frame while the status badge
  read `Complete`: most of the canvas kept the flat low-resolution preview and
  only a lower-right region showed refined detail. This matches the reported
  ES2 symptom but is recorded separately from the controlled count. No
  recovery pan or checkpoint followed in the log.
- Trials 5–8 used F7 followed by a manual drag. Trial 5 started no render
  (F7 without a pan) and trial 6 returned before `release`, so its completion
  had focus; both are invalid. Trials 7 and 8 are valid offscreen trials:
  `complete` with `focused=false`, focus returned about 5.4 s and 4.8 s later.
  Their window verdicts were not recorded, and trial 8 was followed by zoom
  input, so both are unobserved.
- A second natural, ungated drag in the same process reached `complete` with
  focus about 0.72 s before focus loss, so it is inconclusive as an offscreen
  trial. After a Space swipe away and back about 10 s later, an unaided
  full-screen capture showed the complete frame with the status badge
  `Complete`. In the earlier natural attempt with a mixed frame, focus was lost
  about 0.24 s after completion; two observations do not establish a timing
  dependence.
- Trials 9 and 10 (F7 and a manual drag) are valid offscreen trials; focus
  returned about 3.9 s and 3.4 s after completion. Their window verdicts were
  not recorded, and trial 10 was followed by zoom input, so both are
  unobserved.
- Trial 11 (F7 and a manual drag, two drag requests) is a valid offscreen
  trial with a visible failure. Focus was lost, `release` followed after 3 s,
  and `frame_promoted` and `complete` were logged with `focused=false`. The
  operator swiped back about 1.7 s after completion. A full-screen capture
  taken without application input (no further render in the log) showed a
  mixed frame while the status badge read `Complete 4.21 s`: the left part of
  the canvas kept the flat, blocky low-resolution preview and only a right
  region showed refined detail. The view had been zoomed to a scale of about
  1.5e-8 before this trial, deeper than the shallow zoom asked for above; the
  driver always renders with the direct-double backend, so this changes render
  time, not the presentation path. After the failure was recorded, a small
  manual pan started one new render that completed with focus, and a
  full-screen capture showed the complete frame: the pan recovered the
  display.
- Trial 12 (F7 and a manual drag, scale about 8.7e-4) is a valid offscreen
  trial with a visible failure: `frame_promoted` and `complete` with
  `focused=false`, swipe back about 1 s later, no further render before an
  unaided full-screen capture. The window showed refined detail only in a left
  region of about 45% of the width; the rest of the canvas, including a strip
  along the bottom, was a flat preview color, while the badge read
  `Complete 6.33 s`. A small manual pan then started one new render that
  completed with focus, and the operator reported the complete frame: the pan
  recovered the display.
- Running ES2 count: controlled valid 8, of which 2 visible failures and 6
  unobserved; invalid 4. Natural: 2 inconclusive, one with a visible mixed
  frame and one with a complete frame.

Metal, same build, session and window size, in a new process; the log
confirmed `MTLPipeline`:

- Trial 1 (F7 and a manual drag) is invalid: the operator swiped back about
  1.7 s after `release`, while the held render was still calculating, and
  `frame_promoted` and `complete` arrived with focus about 0.5 s later. The
  operator saw the progressive render continue after returning, which is the
  expected display of unfinished work, not a presentation result.
- Trial 2 (F7 and a manual drag, scale about 8.2e-4) is a valid offscreen
  trial with a pass: `frame_promoted` and `complete` with `focused=false`,
  swipe back about 2.8 s later, no further render before an unaided
  full-screen capture, which showed the complete refined frame with the badge
  `Complete 5.45 s`.
- Trial 3 (F7 and a manual drag, scale about 8.2e-4) is a valid offscreen
  trial with a pass: `complete` with `focused=false`, swipe back about 5.5 s
  later, no further render before an unaided full-screen capture, which
  showed the complete refined frame with the badge `Complete 5.44 s`.
- Trials 4, 5 and 6 (F7 and a manual drag, same scale) are valid offscreen
  trials with passes: each logged `complete` with `focused=false`, the
  operator swiped back about 8.0 s, 8.1 s and 11.0 s later, and no further
  render preceded an unaided full-screen capture. Each capture showed the
  complete refined frame, with badges `Complete 4.95 s`, `5.10 s` and
  `5.01 s`.
- Trials 7, 8, 9 and 11 (F7 and a manual drag, same scale) are valid
  offscreen trials with passes: `complete` with `focused=false`, swipe back
  about 0.9 s, 2.9 s, 2.4 s and 7.3 s later, and no further render before an
  unaided full-screen capture showing the complete refined frame (badges
  `Complete 5.91 s`, `5.60 s`, `6.47 s` and `6.75 s`). Trial 7 returned about
  as soon after completion as the failing ES2 trial 12.
- Trial 10 is valid, with focus returning about 0.05 s after offscreen
  completion, but has no recorded verdict and is unobserved.
- Running Metal count: controlled valid 10, of which 9 passed and 1
  unobserved; no visible failure; invalid 1.

ES2, second process with the same build and session file; the log confirmed
`ES2Pipeline`. Relaunching with the same output name replaced the first ES2
process log, so the private raw log of ES2 trials 1–12 above no longer exists;
those entries were recorded from it before it was replaced.

- Trials 1–5 (F7 and a manual drag, scale about 3.7e-3) are valid offscreen
  trials with visible failures. Each logged `frame_promoted` and `complete`
  with `focused=false`; the operator swiped back about 7.5 s, 13.5 s, 9.3 s,
  8.1 s and 5.5 s later, and no further render preceded an unaided
  full-screen capture. Every capture showed a mixed frame while the badge read
  `Complete` (4.90 s, 5.06 s, 4.96 s, 4.47 s and 4.52 s): a large part of the
  canvas kept the flat, blocky low-resolution preview and only the remaining
  region showed refined detail. A later manual pan started a new render in
  each case; whether it restored the frame was not recorded for these trials.
- Running ES2 count across both processes: controlled valid 13, of which 7
  visible failures and 6 unobserved; no observed pass; invalid 4. Natural: 2
  inconclusive.
- A later relaunch with the same output name also replaced the log of this
  second ES2 process; the entries above were recorded from it before.

ES2, third process with a separate log, Mission Control sequence:

- Trial 1 (F7) started no render before focus loss and is invalid.
- Trial 2 (F7 and a manual drag, scale about 3.7e-3) is a valid offscreen
  trial: `frame_promoted` and `complete` with `focused=false`. After
  completion the operator opened Mission Control without selecting the
  original Space. Two full-screen captures show its thumbnail with the flat
  preview color over the left part of the window and refined detail only on
  the right: a thumbnail failure. After returning about 76 s after completion,
  an unaided capture showed the same mixed window (badge `Complete 4.85 s`):
  a window failure. Recovery was not recorded.
- With trial 2 still unrecovered and no further render in the log, a partial
  Space swipe toward the application showed the same mixed frame (capture
  during the gesture): a partial-swipe failure for the same completed frame.
- ES2 runs ended here: the symptom reproduced in every controlled trial with
  a recorded verdict, in the window, the Mission Control thumbnail and the
  partial swipe.
- No logged natural attempt was run in a fresh process on either pipeline.
  The operator reports earlier natural attempts without F7 or F8 with the
  same outcome, ES2 reproducing the symptom and Metal not; those attempts
  were not logged and are not counted as trials.

Metal, second process with a separate log, Mission Control sequence:

- Trial 1 (F7 and a manual drag, scale about 8.2e-4) is a valid offscreen
  trial: `frame_promoted` and `complete` with `focused=false`, and focus had
  not returned when the captures were taken. A full-screen capture of Mission
  Control shows the original Space thumbnail with the complete refined frame:
  a thumbnail pass. The operator also reported seeing the progressive render
  advance in the thumbnail while the window was offscreen, which ES2 did not
  show. A capture during a partial Space swipe shows the complete frame with
  the badge `Complete 5.67 s`: a partial-swipe pass. The window verdict after
  returning was not recorded.

### Result (2026-10-28)

Controlled offscreen trials with a recorded verdict, one Apple M3 Pro, macOS
27.2, JDK 26.0.1, JavaFX 26.0.2, the probe build of `chore/p17-prism-confirmation-run`
and `-Dprism.order` selecting the pipeline:

| Pipeline | Window | Mission Control thumbnail | Partial swipe | Valid without verdict | Invalid |
| --- | --- | --- | --- | ---: | ---: |
| ES2 | 8 failures of 8 | 1 failure of 1 | 1 failure of 1 | 6 | 5 |
| Metal | 9 passes of 9 | 1 pass of 1 | 1 pass of 1 | 2 | 1 |

ES2 reproduced the reported symptom in every controlled trial with a verdict:
after the frame completed offscreen, the window, the Mission Control
thumbnail and a partial swipe kept a mixed preview and refined frame while the
status badge read `Complete`, and a small pan restored the display in every
case where recovery was checked. Metal showed the complete frame in every
trial with a verdict, and its thumbnail visibly advanced while the window was
offscreen. The run therefore establishes the regression comparison on this
Mac: the defect is specific to ES2 here and was not observed with Metal.

Limits: one Mac; finite trial counts, so the Metal result is bounded evidence
rather than proof; Mission Control and partial swipes have one trial per
pipeline; no logged natural attempt in a fresh process; the two ES2
processes' raw logs for the early trials were overwritten after recording;
and the pipeline was requested with `-Dprism.order`, not through the
startup selection that a default switch would add. That final candidate must
repeat this check before section 4 of the
[Metal migration checklist](../docs/platform/METAL_MIGRATION_CHECKLIST.md)
is ticked.
