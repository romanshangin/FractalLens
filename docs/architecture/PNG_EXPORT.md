# Scene-based PNG export (P2.3)

`ROADMAP.md` section 6 owns high-resolution/off-screen export; section 5 owns
immutable scenes/targets. Implemented; owner accepted P2.3 on 2026-10-27:
[manual macOS acceptance](#manual-acceptance-2026-10-27).

## Contract

File → Export PNG… (macOS Command-Shift-E) captures `FractalScene` before size
editing. Completed interactive frame supplies initial availability/suggested size
and name only, never pixels/lifecycle. Width/height are independent integer sample
counts ≥2. Cancel in either dialog starts nothing; invalid sizes remain editable.

Preserve exact center/decimal vertical span. Mapping spans sample centers 0..size
minus 1; aspect changes horizontal span, keeping square spacing. Dimensions never
enter history. Iterations derive from scene settings, preset default vertical scale
and captured scale, independent of resolution/later navigation. Captured Julia,
palette stops, traps and histogram apply; histogram maps the whole export base.

Export owns CPU-only precision-selecting backend/executors, independent of UI/GPU.
Tiled base selects double/precise Mandelbrot/Julia on output grid; reject unsupported
formula/precision before allocation. Adaptive AA uses global grid/full-frame
neighbors, 32×32 tiles, regular 2×2–8×8 samples, linear-light averages/deterministic
dither. Interactive display/AA cannot reduce export quality.

## Cancellation and file publication

Active command becomes Cancel Export PNG. Navigation/resize cannot alter/cancel
captured export; new service request supersedes its generation. Cancel/close
invalidate callbacks/publication. Coordinator drains actual registered workers
before next export; idempotent close schedules cleanup after drain without FX waits.

Encoding checks cancellation during row copy/byte writes, staging in a unique
sibling temp. Only current completed generation atomically replaces destination.
Cancellation/render/encoding/replacement failure removes staging and preserves
destination; no atomic sibling move means error, never destructive fallback.
Errors name chosen file/cause (e.g. folder write permission), never hidden temp.
Request identities also suppress already-queued stale/cancelled JavaFX completion.

Bottom-left render capsule hosts export progress/outcomes, no filename/top banner.
Outcomes dismiss after four seconds; new export cancels old timer. Monotonic stage
weights: base 0–45%, AA 45–95%, encoding/publication 95% until atomic success (100%).
These are completed stages, not elapsed time/ETA. Cancelled/superseded requests
cannot update/dismiss newer status.

Names include fractal/approximate center, no palette. **Export…** size action
updates rough uncompressed RGBA estimate. Detail-dependent PNG compression makes
this guidance, not prediction/strict size bound.

Window close/Close Window/Quit during export asks to cancel/close. **No, Return**
is initially focused/default/Escape; **Yes, Close** allows cancellation/shutdown.
macOS Quit uses the same FX close handler. Completion/cancel stay inline, no
modal success. macOS errors/decisions use async AppKit `NSAlert`, sizes native
field sheets; other platforms/bridge failure retain JavaFX forms/alerts.
[Native windows](../platform/MACOS_UI.md#native-windows-p24).

## Memory and remaining acceptance

Calculation is tiled but sample/color storage full-frame, not streaming. Before
allocation enforce `min(16,777,216, max JVM heap / 3 / 64)` pixels using overflow-safe
`long` multiplication. Estimate 64 bytes/pixel covers samples/validity/base-output
colors/staging/headroom; reference orbits/interactive frames need extra memory.
3840×2160 fits if heap permits. Conservative admission, not a guarantee against
all memory shortages; larger streaming exports remain future work.

Regressions: non-screen/tile-edge dimensions, all presets/custom Julia, histogram/
traps, deep Mandelbrot/Julia, mixed parity, supersession, base/AA/encoding cancel,
idempotent close/failed publication. JavaFX: size/focus/confirm/cancel, scene/history,
Cancel command during navigation. Startup checks actual focus owner: export-status
wrapper must preserve canvas keys. macOS forks use development-launcher Glass
exports/opens to exercise production startup.

2026-10-14 developer checks: portable 451 executed/95 opt-in skips; 31 active-
desktop macOS menu/dialog tests pass. System-save Julia 3840×2160 PNG dimensions/
image/success status inspected; focus, retained invalid input, Enter/Escape checked.
Found validation layout fixed with existing resize helper and FX suites. Logs/PNG
stay outside repository.

2026-10-14 focus repair: `mvn clean test` 451 executed/96 opt-in skips;
active-desktop `FractalApplicationFxTest`, `MainMenuBarFxTest`, `FractalResizeFxTest`,
`MacSystemMenuFxTest`: 52 executed/two fullscreen skips. New startup regression
fails on status-container focus, passes after canvas focus. Developer validation
only; manual acceptance not repeated this round.

At this stage manual gaps: long-export cancel, concurrent pan/zoom/resize,
overwrite/errors, restored focus, light/dark/fullscreen. Apple Silicon checks do
not validate Windows/Linux desktop/Intel Mac. No new performance/benchmark claim.

## Export UX repair self-validation (2026-10-15)

Repair merges export/render status, drops palette names, dismisses outcomes after
four seconds, adds stage progress/size guidance, renames size action and confirms
close-during-export. Existing branch/user work preserved.

`mvn clean test`: 551 discovered/453 executed/98 opt-in skips. Active Apple Silicon
desktop `FractalApplicationFxTest`, `MainMenuBarFxTest`, `FractalResizeFxTest`,
`MacSystemMenuFxTest`: 56 discovered/54 executed/two fullscreen skips. Regressions
cover monotonic atomic-publication progress, cancel suppression, palette-free names,
live size guidance, Export…/shared badge/dismissal and return/confirmed close.
Base reserves 45% for backend completion because mirrored regions may overlap.

Dark development UI inspected: size layout/Export… and palette-free save name.
Computer-control Save/Cancel left native panel open, no PNG; cause unresolved,
proving neither product nor environment failure. Instance terminated. This repair
still lacks manual end-to-end export, live progress/outcomes, system Quit, light/
fullscreen, Windows/Linux/Intel Mac. Desktop tests are developer checks, not
independent review/verification; P2.3 remained open at this stage.

## Manual acceptance (2026-10-27)

Apple Silicon manual acceptance: `main` at `c30a095`, light/dark, window/fullscreen.
Isolated-profile desktop automation handled most steps; owner checked the rest.

Passed:

- Export PNG… availability, the Command-Shift-E and Command-W shortcuts, the
  native size sheet with the window size and live size estimate, rejected
  drafts (`1`, `abc`, an over-limit width) kept for correction, Tab, Return and
  Escape.
- System save panel with a palette-free suggested name, replacement prompt,
  monotonic stage progress, `PNG saved` and its automatic dismissal.
- Exact output dimensions (1600×1000, 4000×2500, 3456×2168 and others) with no
  tile seams, antialiasing present and no staging files left behind.
- Pan during a deep Julia export: the file shows the captured scene. Undo after
  export removes only the user's own scene changes.
- Cancel Export PNG during base work (10%) and AA work (46%): no output, no
  staging files, and an overwritten destination keeps its original bytes.
- Write into a read-only folder, including a replacement there: native
  `Export failed` alert, `PNG export failed.` badge, destination unchanged.
- Close Window, the window close button and system Quit during export: the
  confirmation defaults to No, Return; Escape and Return keep the export
  running; Yes, Close exits without output.
- Keyboard navigation on the canvas after every dialog.
- Burning Ship, Tricorn with Histogram Coloring, cubic Multibrot with the Cross
  orbit trap, deep Julia and deep Mandelbrot at span 2.4e-20 exports without
  blocks or noise.

Russian input prevented automated canvas keys/drags/FX shortcuts; owner verified
manually, an automation limit rather than product defect. Palette-during-export
was inconclusive (finished too soon); pan and regressions cover scene capture.

Acceptance found hidden staging path/no cause in failure alert; PR #33 fixes it.
No separate independent review/verification. Windows/Linux/Intel Mac portable CI only.
