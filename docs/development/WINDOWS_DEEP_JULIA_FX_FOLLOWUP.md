# Follow-up: Windows deep Julia frame-completion deadline

Status: UI-test deadline correction implemented; CPU latency investigation open.
Separate CPU/JavaFX investigation; not part of Windows Vulkan runtime
implementation or GPU promotion acceptance.

## Observed behavior

`FractalResizeFxTest.deepJuliaCompletesAndRevealsTheCanvasAtWindowResolution`
can fail with `View must complete the new frame` at its 30-second deadline.
The test renders `ReportedJuliaFixture.viewport()` at 1200x780, waits for the new
frame, and checks that completion reveals the canvas after the loading layer.

The 2026-11-03 standalone Windows Vulkan verification reproduced the same failure
in isolation on both the candidate and base revision
`63b5c63ac860799a7ea17f383dd444c19f37ced0`, with GPU disabled. The base run reported
one test, one failure, zero errors and zero skips (31.75-second test duration).
The earlier developer resize run passed near the deadline. This is evidence of a
pre-existing borderline completion deadline, not proof of the underlying cause.
Do not attribute it to the Vulkan session rename or weaken the deadline to hide it.

Environment reported by verification: Windows 11 build 26200, Java 26.0.1,
JavaFX 26.0.2, NVIDIA GTX 1650 Max-Q and Intel UHD, AC power, Balanced power plan.
Private logs remain outside the repository in the platform-temp
`fractallens-verify-win-gpu` directory: `fx-deepjulia-base.log`,
`fx-resize-rerun.log`, `fx-all.log` and `fx-gpu-enabled.log`. Raw logs and personal
paths are not included in the checkout. This record summarizes supplied verification
and inspected base-run output; it does not claim a fresh reproduction.

## Reproduction and investigation

Run in an active Windows graphical session using the pinned Maven wrapper, a fresh
platform-temp JavaFX cache, `reuseForks=false`, `fractal.fx.tests=true`,
`fractal.gpu.enabled=false`, and
`-Dtest=FractalResizeFxTest#deepJuliaCompletesAndRevealsTheCanvasAtWindowResolution`.
Compare the fixed base with the candidate under matching power/thermal conditions.

Separate render scheduling, CPU deep-Julia calculation, cancellation/draining,
JavaFX publication and loading-layer completion. Record first-visible and complete
frame latency and verify the frame belongs to the requested viewport. Preserve
precision requirements and deterministic state waits. A timeout increase alone is
not a diagnosis or acceptance result.

## Acceptance

- Identify the root cause and distinguish product latency/publication defects
  from machine-load or test-harness limits with reproducible evidence.
- Fix any demonstrated defect with a focused regression test; preserve complete
  frame visibility, cancellation and precision contracts.
- Run the isolated test, relevant JavaFX resize checks and portable suite; report
  timing margin and any remaining platform limits without marking skips as passes.
## UI-test contract correction

The full-window test now requires observable nonzero rendering progress (or an
already completed frame) within 15 seconds, then waits on the rendering-completion
callback with the same 90-second hang guard used by the adjacent window-resize
completion helper. Pixel dimensions, reported decimal viewport, real CPU backend,
iteration policy and final visibility/viewport assertions are unchanged. No sleeps
or performance shortcuts were added. A stalled start fails the progress check;
progress alone cannot pass the completion/visibility assertions.

The previous unconditional 30-second total deadline was an undocumented portable
performance threshold in a UI completion test. Existing diagnostics in
`JULIA_DEEP_ZOOM_PERFORMANCE.md` already document longer correct full-window Julia
renders; supplied base/candidate reproduction places this machine at the deadline.
The correction separates responsiveness/completion from throughput acceptance.
It does not prove CPU latency acceptable or close the remaining investigation.