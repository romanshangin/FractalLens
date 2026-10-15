# Scene-based PNG export (P2.3)

Section 6 of `ROADMAP.md` owns the consolidated high-resolution and off-screen
export requirements. Section 5 supplies the immutable scene/target model.
Implementation is complete; product acceptance remains open pending independent
review/verification and manual UI checks.

## Contract

File → Export PNG… (Command-Shift-E on macOS) captures the current `FractalScene`
before opening the size editor. A completed interactive frame is required only
for initial availability and the suggested dimensions/name. The export does not
read its pixels or depend on its calculation lifecycle. Width and height are
independent integer sample counts of at least 2. Cancel in either dialog starts
no work. Invalid size drafts stay editable in the size dialog.

The scene center and exact decimal vertical span remain unchanged. The existing
viewport mapping spans sample centers (0 through size minus 1); a changed aspect
ratio changes the horizontal span, with square pixel spacing. Dimensions never
enter scene history. The iteration budget is derived from the scene's iteration
settings, the preset's default vertical scale, and the captured scene scale;
it does not depend on output resolution or subsequent interactive changes.
Julia constants, custom palette stops, orbit traps and histogram coloring come
from the captured scene. Histogram mapping uses the entire export base frame.

The export owns a CPU-only precision-selecting backend and executors, separate
from interactive rendering and optional GPU settings. Existing tiled CPU base
renderers select direct double or precise Mandelbrot/Julia calculation using
the output grid. Unsupported formula/precision combinations fail before frame
allocation. Adaptive AA uses 32×32 output tiles over the same global grid and
full-frame neighbor data, preserving 2×2–8×8 regular subpixel sampling, linear-light
color averaging and deterministic dithering. Interactive AA/display settings do
not reduce export quality.

## Cancellation and file publication

While exporting, the File command becomes Cancel Export PNG. Navigation and
resizing do not change or cancel the captured export. Starting another service
request supersedes the previous generation. Cancellation and close invalidate
callbacks and publication; the coordinator waits for actual registered worker
exit before the next export. Close is idempotent and schedules backend/executor
cleanup after that drain, without waiting on the JavaFX thread.

PNG encoding checks cancellation while copying rows and writing encoded bytes.
The result is staged in a unique sibling temporary file. Only a current,
completed generation may atomically replace the destination. Cancellation before
that publication,
render failure, encoding failure or failed replacement removes staging data and
preserves the destination. A filesystem without atomic sibling moves reports
an error rather than using a potentially destructive replacement fallback.
UI callbacks also carry a request identity so already queued JavaFX callbacks
cannot report a cancelled or older export as completed.

Export progress and outcomes share the existing bottom-left render capsule;
no filename or separate top banner is shown. Outcomes disappear after four seconds,
and a new export cancels the previous dismissal timer. Progress is monotonic and
stage-weighted: base tiles cover 0–45%, AA tiles 45–95%, and encoding/publication
holds at 95% until atomic replacement succeeds (100%). These percentages describe
completed work stages, not elapsed time or an ETA. Cancelled/superseded requests
cannot update progress or dismiss a newer status.

Suggested filenames include the fractal and approximate center, without a palette.
The size editor uses **Export…** and updates a rough uncompressed RGBA size estimate
as dimensions change. PNG compression depends on image detail, so this is an
orientation value rather than a prediction or strict file-size bound.

Closing the window or choosing Close Window/Quit during an active export asks
whether to cancel it and close. **No, Return** is initially focused, is the default
button, and handles Escape; **Yes, Close** permits normal shutdown and cancellation.
The macOS system Quit reaches the same JavaFX window-close request handler.
Completion and cancellation appear inside the window without a modal success
alert. Errors retain the existing alert path. The size editor uses JavaFX on all
platforms; native AppKit forms are the separate P2.4 task.

## Memory and remaining acceptance

This is tiled calculation, with full-frame sample/color storage rather than an
out-of-core PNG pipeline. Validate before allocating: pixel count must not exceed
`min(16,777,216, max JVM heap / 3 / 64)`. Multiplication uses `long`, preventing
integer overflow. The 64-byte estimate covers base samples, validity, base/output
colors and image staging with headroom; reference orbits and the live interactive
frame still consume additional memory. The limit supports 3840×2160 when the JVM
heap budget permits it. It is a conservative admission limit, not a guarantee
against every system memory shortage. Larger streaming exports are future work.

Developer regressions cover arbitrary non-screen dimensions across tile edges,
all presets, custom Julia constants, histogram and orbit-trap coloring, deep
Mandelbrot/Julia and mixed dimension parity, supersession, cancellation during
base/AA work, idempotent close, failed publication, and encoding cancellation.
JavaFX regressions cover size validation, focus, confirming/cancelling the form,
scene/history preservation and cancellation-command availability during navigation.
The application startup regression checks the actual scene focus owner after
launch: the canvas retains keyboard navigation when wrapped by the export-status
container. On macOS, test forks use the same Glass exports/opens as the development
launcher to exercise this production startup path.

Developer checks on 2026-10-14 passed the portable suite (451 executed tests,
95 opt-in tests skipped) and 31 active-desktop macOS JavaFX menu/dialog tests.
A running macOS application exported a Julia scene through the system save panel
at 3840×2160; the PNG dimensions and image were inspected, and the in-window
success status was observed. Form focus, retained invalid input, Enter and
Escape were also exercised. A validation-layout issue found during inspection
was corrected using the existing dialog-resizing helper and checked by the
JavaFX suites. Raw logs and the test PNG remain outside the repository.

The startup-focus repair round on 2026-10-14 passed `mvn clean test` (451
executed, 96 opt-in skips) and an active-desktop run of
`FractalApplicationFxTest`, `MainMenuBarFxTest`, `FractalResizeFxTest` and
`MacSystemMenuFxTest` (52 executed, two fullscreen tests skipped). The new
startup regression first failed on the export-status container as focus owner
and passed after directing initial focus to the canvas. These are developer
self-validation results; this repair round did not repeat manual UI acceptance.

Manual acceptance still remains for cancellation during a long export,
continuing pan/zoom/resize while export runs, overwrite/error handling,
focus restoration, light/dark appearance coverage and full screen. Windows/Linux desktop and Intel macOS
behavior are not established by Apple Silicon macOS developer checks. No new
performance claim or benchmark result is made.

## Export UX repair self-validation (2026-10-15)

This repair combines export status with the render capsule, removes palette names
from suggested filenames, clears outcomes after four seconds, adds stage progress
and size guidance, renames the size-editor action, and confirms closing during
export. The existing task branch and its pre-existing work were preserved.

`mvn clean test` passed: 551 discovered tests, 453 executed, 98 opt-in skips.
The active Apple Silicon macOS desktop run of `FractalApplicationFxTest`,
`MainMenuBarFxTest`, `FractalResizeFxTest` and `MacSystemMenuFxTest` passed:
56 discovered, 54 executed, two fullscreen skips. Regressions cover monotonic
progress through file publication, suppression after cancellation, removal of
palette names, live size guidance, the Export… action, shared badge text,
automatic outcome dismissal, and return/confirmed-close behavior during export.
Base progress reserves the 45% boundary for backend completion because mirrored
region notifications may overlap.

The running development application was visually inspected in dark appearance:
the size-editor layout and Export… action were visible, and the system save panel
suggested a filename without a palette. This manual export did not complete:
Save/Cancel interactions through computer control left the native panel open and
no PNG was created. The cause is unresolved; this observation does not establish
either a product defect or an environment-only failure. The test instance was
terminated. End-to-end manual export, live outcome/progress appearance, macOS
system Quit during export, light appearance, fullscreen, Windows/Linux desktop
and Intel macOS remain unverified for this repair. Automated desktop checks are
developer self-validation, not independent review or verification. P2.3 acceptance
remains open.
