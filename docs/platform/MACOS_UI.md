# macOS interface

The window contains a fractal canvas and standard frame. `MainMenuBar` uses
JavaFX system integration: display-top menus on macOS, in-window menus elsewhere.
Rendering, precision limits and pointer gestures are unchanged.

Plain `FractalLauncher` runs before JavaFX; on macOS it sets `mtl,es2,sw` only
when `prism.order` is absent, preserving explicit overrides. Metal addresses
Spaces reliability with an owner-accepted latency cost. Final acceptance:
[migration checklist](METAL_MIGRATION_CHECKLIST.md).
[Startup/overrides](MACOS_RUN.md#javafx-startup-and-prism-selection).

Registering commands as JavaFX's application-default system menu prevents Glass
from replacing them with an empty menu on focus loss during Spaces activation.
Commands survive Space/app switches without pointer input over the menu bar.
View close clears registration and releases its commands.

Run the native menu regression in an active macOS desktop session:

```shell
mvn -Dfractal.fx.tests=true "-Djavafx.cachedir=${TEMP_DIR}/fractallens-system-menu" -DreuseForks=false -Dtest=MacSystemMenuFxTest test
```

The regression inspects AppKit before focus loss, during focus on an unowned
menu-less window, after focus return and after disposal. A gated CPU render checks
every JavaFX/AppKit Color/Render action during startup, completion, another render
and its completion. Updating AppKit during the first render simulates open-menu
validation; completion assertions neither refresh menus nor supply pointer input.

2026-10-03, macOS 27.0 arm64/JDK 26.0.1/JavaFX 26.0.2: original regression
reproduced disabled native Edit Palette after completion. Fix: both native menu
and all five MainMenuBar tests passed without skips; `mvn test`: 400 executed,
63 opt-in skips. Development Julia renders showed accessibility actions disabled
during calculation and enabled after completion in the same open Color/Render
menus, without pointer input. Deep Zoom Antialiasing stayed unavailable in standard
mode; palette shortcut opened the editor, then Cancel closed it. Screenshot capture
failed: painted enabled/disabled states unverified. Windows/Linux runtime not run.

For visible acceptance, switch to another Space, activate another application,
then return to FractalLens without moving the pointer onto the menu bar and check
that File through Help remain visible and usable.

2026-09-29: original code lost all nine menus on focus loss; registration fix
passed the native regression (1 test, no skips). `mvn test`: 399 executed,
60 opt-in skips. User confirmed Spaces reproduction sequence and Help opening
in the development build; process inspection excluded an older packaged app
with the same bundle identifier.

The system application menu is named **FractalLens**, including its Hide and Quit
commands. `-Xdock:name` alone does not reliably name this menu in JavaFX 26.
`MacApplicationMenu` updates the Glass application menu after installation and
when the main window regains focus. This small adapter isolates an internal
JavaFX API because the public MenuBar API cannot edit the application menu.

`mvn javafx:run` automatically enables the required module access on macOS.
IntelliJ users can select the shared **FractalLens (macOS)** run configuration.
Custom run configurations and packaged launchers need these VM options:

```text
-Xdock:name=FractalLens
--add-exports=javafx.graphics/com.sun.glass.ui=com.shangin.fractal
--add-opens=javafx.graphics/com.sun.glass.ui.mac=com.shangin.fractal
```

The name takes effect on restart. When packaging with `jpackage`, also use
`--name FractalLens`. Recheck the adapter when upgrading JavaFX.

## Application icon

Startup `ApplicationIcon` loads bundled 1024×1024 RGBA
`src/main/resources/com/shangin/fractal/app/icons/fractallens.png`. macOS Maven/shared
IntelliJ supplies absolute `FractalLens.icns` via `-Xdock:icon`, setting the native
icon before JavaFX; setting only `Application.start()` flashed Java's generic icon.
Custom launchers retain public `Taskbar` fallback; other platforms use `Stage.getIcons()`.
macOS has no window document-proxy icon. Restart to see changes.

For a macOS application bundle, pass these additional `jpackage` options:

```text
--name FractalLens
--icon src/main/resources/com/shangin/fractal/app/icons/FractalLens.icns
```

ICNS includes standard/Retina sizes through 1024 pixels. Matte artwork removes
highlights, glow, gradients and bevels. Unmasked square/native Icon Composer sources:
`design/app-icon/`; glossy original: `design/icon-concepts/`. With Pillow, rebuild
PNG/ICNS/foreground using `python3 scripts/packaging/build_app_icon.py`. Apple's
native-export compatibility mask affects flattened Java/ICNS only, never native
layers. Native compilation/packaging: `design/app-icon/README.md`.

Custom Java launch configurations must supply this VM option before the main class
(replace the project path; retain quotes when it contains spaces):

```text
"-Xdock:icon=/path/to/FractalLens/src/main/resources/com/shangin/fractal/app/icons/FractalLens.icns"
```

API references: [Java Taskbar](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/Taskbar.html),
[JavaFX Stage icons](https://openjfx.io/javadoc/26/javafx.graphics/javafx/stage/Stage.html#getIcons()),
[Java launcher options](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html#extra-options-for-macos).

## Commands

The initial canvas uses 75% of the primary screen's width and height, preserving
the screen proportions, and the window opens centered.

Fullscreen/window resize share behavior. At initial view, stretch the completed
image immediately; after 75 ms stable size, render the fitted frame and replace
preview atomically. Hide intermediate tiles to prevent mixed-aspect side bands/
split contours. Zoomed resize preserves center/render-pixel spacing: expansion
reuses center/AA and computes exposed borders; shrinking a complete frame crops
without rendering. Subsequent pan retains the resized iteration limit and computes
only exposed strips. Equal dimension parity keeps centered copies on integer
pixels. Interrupted AA publication completes missing display tiles even with
cached samples.

The native JavaFX regression checks can be run with
`mvn -Dfractal.fx.tests=true -Dtest=FractalResizeFxTest test` on a desktop session.

| Menu | Commands |
| --- | --- |
| File | Export PNG… (⌘⇧E), Settings… (⌘,), Close Window (⌘W) |
| Edit | Standard text editing commands for focused text fields |
| View | Zoom In (⌘+, also ⌘=), Zoom Out (⌘−), Reset View (⌘0), Go to Coordinates… (⌘L), Enter/Exit Full Screen (⌃⌘F) |
| Fractal | Mandelbrot, Julia, Multibrot, Burning Ship, Tricorn |
| Color | Palette presets, Edit Palette… (⌘⇧P), Orbit Trap, Histogram Coloring, Animate Palette, Reset Colors to Defaults |
| Render | Edit Iteration Settings…, Edit Julia Parameters… |
| Window | Minimize (⌘M), Zoom |
| Help | FractalLens Help, including pointer and keyboard navigation, the render status badge and Render Settings |

Settings… is available at any time, including while a frame renders; applying it
restarts the render. Edit Iteration Settings is available for every fractal when
rendering is idle. Edit Julia Parameters is enabled only for Julia. See
[Render settings (P2.6)](#render-settings-p26).

Other platforms use their shortcut modifier instead of Command. Focused-canvas
arrows pan after zooming; Page Up/Down zoom centrally, Home resets. Pointer-centered
scroll, drag and trackpad remain; menu/key zoom preserves center. Right-click
**Copy Coordinates and Zoom** copies exact decimal real/imaginary center and zoom
as shareable plain text. Renderer-independent `FractalContextMenu` owns commands/
lifecycle. macOS uses AppKit `NSMenu` via Objective-C/JNI
`src/main/macos/FractalContextMenu.m`: native appearance, highlighting, keyboard
navigation/tracking, no CSS/custom styling. Styled JavaFX fallback covers other
platforms, unavailable native library or missing JavaFX module access.

Create/cancel native sessions on the JavaFX/AppKit main thread. Native-run-loop
scheduling returns from Java before tracking; selection uses `Platform.runLater`
after tracking. Close/replacement suppresses pending callbacks and releases JNI
global references. Movement, resize, focus loss, scene detachment and canvas
commands cancel tracking. AppKit-consumed input follows system-menu behavior;
JavaFX canvas input uses the existing dismissal filter.

Opening is scheduled in the default run-loop mode so a replacement waits for
the previous menu's tracking loop to unwind. Scheduling it in common modes can
prematurely open and cancel the replacement. The item's target/action explicitly
ends tracking, including when accessibility activates it directly. Both cases
are covered by the native regression suite.

Positioning uses the owning window's content view in logical points, including
its flipped-coordinate convention. There is no manual Retina multiplication or
conversion through the primary screen's height. AppKit positions the popup
within the current display's available area.

`macos-native-menu` builds universal `arm64`/`x86_64` `libfractal-menu.dylib`
during `process-classes` into the JAR; both `mvn javafx:run` and `mvn package` run
it. Building needs macOS SDK/Xcode Command Line Tools; packaged execution does not.
Before direct IDE launch, run `mvn process-classes` for output-directory natives.
Custom launchers need native access plus:

```text
--enable-native-access=javafx.graphics,org.lwjgl,com.shangin.fractal
--add-exports=javafx.graphics/com.sun.javafx.stage=com.shangin.fractal
--add-exports=javafx.graphics/com.sun.javafx.tk=com.shangin.fractal
```

The library is extracted to a process-specific temporary file and scheduled for
removal at JVM exit. The normal Maven launch and shared macOS run configuration
already grant the JavaFX peer access. Distribution signing/notarization must
include this library along with the other native dependencies.

See [AppKit popup positioning](https://developer.apple.com/documentation/appkit/nsmenu/popup(positioning:at:in:)?language=objc)
and [native tracking cancellation](https://developer.apple.com/documentation/appkit/nsmenu/canceltrackingwithoutanimation()).

Coordinates use an owner sheet with Go/Cancel, exact invalid-draft feedback,
copyable zoom and deep-zoom status. Palette uses a nonmodal live-gradient draft
editor with Apply/Cancel: only Apply changes the palette. Editing a preset clears
its checkmark to indicate a custom gradient.

Color and Render remain openable during startup and later renders. Their action
items, including palette, orbit-trap and quality choices, are explicitly
disabled while the renderer is busy. Completion updates every action's native
state even when a menu was opened during rendering; hovering or reopening is not
required. Disabling only the parent menu lets AppKit validation leave child items
inactive after the JavaFX parent is re-enabled.

Deep AA is available only in deep zoom. Histogram/orbit traps disable palette
animation; navigation/scene changes stopping it clear its checkmark. After a
completed frame, export captures the scene through independent width/height and
system-save dialogs, then renders off-screen. Active **Cancel Export PNG** remains
available during navigation. Completion is inline/nonmodal; errors use alerts.
macOS size editor is AppKit with shared JavaFX fallback.
[Export contract/acceptance gaps](../architecture/PNG_EXPORT.md).

PNG export progress shares the bottom-left render indicator. Results clear after
four seconds. Export filenames omit the palette; the size editor offers Export…
and an approximate size before compression. Closing during export asks for
confirmation, with No, Return as the default/Escape action; Yes, Close cancels the
export and continues shutdown. macOS system Quit uses the same close-request path.

Color → Palette order: default Ice, Ocean, Fire, Plasma, Forest, Grayscale,
Aurora, Sunset, Twilight, Coral, Desert, Emerald, Amethyst, Copper, Blue & Gold.
The eight Aurora–Copper presets deepen shadows and brighten/saturate highlights.
Blue & Gold cycles navy, blue, cyan, cream, gold, orange and brown. Built-ins have
at least five distinct stops; custom palettes may have two.

Color → Reset Colors to Defaults restores the startup Ice palette and its
original stops, the default color scale, and zero offset. It turns off histogram
coloring, orbit traps, and palette animation, updates the menu checkmarks, and
renders the current view again. Fractal, viewport, iteration, Julia, and
antialiasing settings are preserved.

## Design references and implementation boundary

- [Designing for macOS](https://developer.apple.com/design/human-interface-guidelines/designing-for-macos/)
- [Menus](https://developer.apple.com/design/human-interface-guidelines/menus)
- [Going full screen](https://developer.apple.com/design/human-interface-guidelines/going-full-screen)
- [JavaFX MenuBar](https://openjfx.io/javadoc/26/javafx.controls/javafx/scene/control/MenuBar.html)

This is a JavaFX implementation with native macOS menus, window controls, and
file chooser. Iteration Settings, Julia Parameters and Add Bookmark use native
AppKit sheets with JavaFX fallback. The coordinate and gradient editors use JavaFX controls, not
AppKit or SwiftUI. Scene controls are grouped under their task menus; there
are currently no separate application-wide preferences.

## Validation

Native canvas menu checks (require an unlocked, focused desktop session):

```sh
mvn -Dfractal.fx.tests=true -Dtest=MacContextMenuFxTest \
    -Djavafx.cachedir=/tmp/fractallens-javafx-cache test
mvn -Dfractal.fx.tests=true -Dfractal.fx.fullscreen=true -Dtest=MacContextMenuFxTest \
    -Djavafx.cachedir=/tmp/fractallens-javafx-cache test
mvn -Dfractal.fx.tests=true \
    '-Dtest=FractalResizeFxTest#contextMenuDismissesWithoutSwallowingCanvasInput' \
    -Djavafx.cachedir=/tmp/fractallens-javafx-cache test
```

Native tests cover AppKit target/action, cancellation/stale selection, scene
detachment, resize, repeated close and publication during tracking. Optional
fullscreen visits every display and enters/exits fullscreen. A test-only FFM probe
inspects tracking/invokes NSMenu actions, without JavaFX substitution/global keys.
The JavaFX-menu test directly covers fallback. Use separate processes or
`-DreuseForks=false`: these classes shut down JavaFX.

2026-09-10: macOS 26.6.2, Apple M3 Pro, JDK 26.0.2, JavaFX 26.0.2, built-in
3456×2234 Liquid Retina XDR. Three native tests and JavaFX fallback passed in
separate JVMs: frame completion/publication during NSMenu tracking, resize,
detachment, stale-selection suppression, immediate replacement, target/action,
fullscreen entry/exit. Probe supplies no separate cancellation: production action
must finish tracking.

Running app: right-click exposed native accessibility `menu`/Objective-C `choose:`.
Escape, outside click and app focus change dismissed it. Keyboard/accessibility
selection closed it and updated clipboard. Pasting into a temporary coordinate
draft verified exact center and `Zoom: 1.25`, then `Zoom: 1` after initial-view
restart. Cancel discarded the pasted draft.

JAR contains arm64/x86_64; runtime checked only on arm64, not Intel/Windows.
Final `mvn package`: 405 tests, 0 failures/errors, 42 opt-in skips. Separate GUI:
four selected tests pass, no skips; reports `target/native-menu-validation/`.
`git diff --check` passed. One physical display only: multi-display/mixed-DPI/
negative-origin placement remains a separate roadmap hardware gate.

Resize: `mvn -Dfractal.fx.tests=true -Dtest=FractalResizeFxTest test`.
Add `-Dfractal.fx.fullscreen=true` for two native fullscreen/window cycles at
approximately 650,000× and 426 billion×. macOS windows check full coverage/center
pixels, saving `target/fullscreen-zoom-60-qa.png` and
`target/fullscreen-zoom-120-qa.png`. No-op scroll/click must preserve pending work.
Zoomed expansion immediately shows completed border base pixels; AA replaces
in place without clearing base/revealing a transparent stair-step. Reported
trackpad fixture runs eight separate zooms, awaiting complete refinement each time.

`mvn -o test`: 299 tests, 0 failures, 0 errors, 2 skipped native GPU tests.
The final UI changes also pass `mvn -o -q -DskipTests compile`.

Checked in a locally packaged macOS app: canvas-only window, native menu
commands, formula selection, keyboard zoom, stable center coordinates,
coordinate validation, gradient Apply/Cancel, full-screen entry and exit,
and the native PNG save sheet. The save sheet was opened without saving a file.

The application-menu fix was checked on Microsoft OpenJDK 25.0.4 with JavaFX
26.0.2. A test bundle deliberately named MenuNameCheck displayed **FractalLens**
in the actual native menu, with **Hide FractalLens** and **Quit FractalLens** items.

The icon was checked in Finder using a locally packaged `.app`. A separate
Java launch on Microsoft OpenJDK 25.0.4 read back the actual native Dock image:
1024×1024 with transparent corners and the selected artwork. The macOS window
has no document-proxy icon. PNG alpha and all eight ICNS representations were
also decoded and checked successfully.

Matte icon opened in Icon Composer and compiled with Xcode `actool`. JavaFX
`-Xdock:icon` launch read native Dock images before/after `ApplicationIcon.install()`:
both match Mandelbrot (mean RGB difference 2.24/255 at 128×128, native resampling
allowed). Maven forwarded the correct absolute ICNS. Compilation and
`git diff --check` passed. Covers startup flash, not just initialized icon.

### Viewport mode indicator

A compact translucent badge sits 12 logical pixels from the bottom-left corner
of the viewport. A blue dot marks **Standard** rendering; a soft coral dot marks
**Deep Zoom**, following the controller's actual precision policy. The badge is
informational, with no click action, and is not included in PNG exports.

macOS badge: content-view AppKit overlay from `libfractal-menu`
(`FractalStatusBadge.m`, `MacStatusBadge`). SDK 26+ on macOS 26+ uses Liquid Glass
`NSGlassEffectView`; older SDK/OS uses within-window popover `NSVisualEffectView`.
Older SDK builds compile without Liquid Glass. Small system font: 11 pt medium,
tabular digits; native mini spinner/tooltip. It consumes clicks/scroll/gestures,
exposes text/details to accessibility, is created on show and released on hide/
close, and rises above later loading material. `MacStatusBadgeFxTest` reads native
state: topmost after loading attachment, 12-point left/bottom inset after resize.
Unavailable bridge/Windows/Linux use JavaFX label (11 px system font, 12 px tooltip).

The badge stays visible over the loading screen, so the first render of a
restored deep-zoom scene shows its mode and progress instead of only the icon.
Its width reserves every digit and a three-digit percentage, so progress
changes never resize it; it changes width only when the state or wording does
(for example `Rendering 42%` → `Complete 7.50 s`).

Help → FractalLens Help explains **Render status**: Standard/Deep Zoom and dot
colors; `Waiting`, whole-frame/AA `Rendering N%`, `Complete`/duration, `Interrupted`,
`Failed`; PNG progress/outcomes in the badge. **Render Settings** explains File →
Settings… standard/deep Base only, Base, then antialiasing, Antialiased display.
Native/JavaFX Help share text; both scroll.

Badge shows exact ready-pixel base progress (including reuse), refinement state
and latest viewport duration without hover. Tooltip contains only `Zoom: …` and
`Iterations: …` (effective limit), not mode descriptions/duration. Below one
million: integers; larger: three significant digits in powers of ten, approximation
mark if rounded. Cancel/failure is never a completed duration; render/recolor
failures use application errors. Post-completion deep AA adds refinement time,
excluding idle; palette animation/export never replace viewport timing.

P1.1 validation on an active macOS 27.0 desktop: a Page Up render from a restored
deep Mandelbrot scene visibly advanced through 0%, 30%, and 68% before showing
`Complete 7.50 s`. The opt-in JavaFX regression starts from a completed frame,
injects a recolor failure on the worker path, and checks the error dialog's
message in the application window. This is functional evidence, not a render
performance measurement.

### Render settings (P2.6)

File → Settings… (⌘,) replaces Display Quality/Deep Zoom Antialiasing menu items
with one application window for future settings; currently **Render Settings** only.
macOS uses the parameter-sheet bridge's owner AppKit sheet, right-aligned labels/
hints and `NSPopUpButton` fields. Bridge failure/Windows/Linux use shared JavaFX
combo boxes. Return/Apply commits; Escape/Cancel discards. Reset to Defaults changes
only drafts (standard Antialiased display, deep Base only) until Apply. Initial
focus is Standard zoom; native focus requires macOS Keyboard navigation. With it
off, AppKit skips pop-ups/buttons like System Settings: no focus/stale ring,
Return/Escape still work. Native text is unselectable; hints cannot take focus.
With Keyboard navigation on, Tab visits only pop-ups/buttons. Enabled during
rendering: Apply cancels/restarts rendering. File hosts the command because
JavaFX's public API cannot add to the system-owned application menu.

Render Settings offers the same three presentations for standard zoom and for
deep zoom. Both are stored in the scene's `AntialiasSettings` as
`InteractiveRenderMode` values (`renderMode` and `deepZoomRenderMode`):

| Mode | Scene value | Presentation |
| --- | --- | --- |
| Base only | `BASE_ONLY` | Progressive base samples; the frame completes without antialiasing |
| Base, then antialiasing | `FAST` | Progressive base samples, then antialiased tiles in place |
| Antialiased display | `REFINED` | Only refined antialiased tiles are shown |

Defaults preserve earlier behavior: standard Antialiased display, deep Base only.
Mandelbrot/Julia requiring precise rendering use the deep setting, all other
frames the standard setting; both remain editable at any zoom. Deep AA uses
candidate-only `refineDeep`; Antialiased display shows refined tiles only and
may take much longer than base rendering.

Base only completes at `RenderStatus.COMPLETE` after base, without refinement.
All modes show whole-frame `Rendering N%`, no separate `Refining…`. Base only
counts ready samples; AA modes allocate half to base (50%) and half to published
AA pixels. Reuse counts immediately: a half-width pan of a complete frame starts
near 50% in every mode. Progress is monotonic; AA modes reach 100% only on last
AA publication. Base-only switching removes displayed AA, including interrupted-
render retained previews, and hides cached AA from recoloring. Smooth-only cached
samples remain for reuse. On completed frames, all colorings (Histogram Coloring/
Orbit Trap included) retain removed AA colors/mask: switching back with unchanged
coloring restores them without AA work. Palette/coloring changes discard retained
colors; smooth cache still avoids new subsample calculation. Deep-AA reference
orbits build only when new subsamples are needed.

When the visible frame is complete and the new scene differs only in its
antialiasing modes, the controller changes the presentation of that frame in
place instead of starting a render; no samples are recalculated. This also holds
for Undo and Redo of the settings, because restoring such a scene keeps the AA
mask of the visible frame:

| Change | Result |
| --- | --- |
| To Base only | The visible frame is recolored from its base samples; its AA colors and cached AA samples are kept |
| Between Base, then antialiasing and Antialiased display on a fully antialiased frame | Nothing is recalculated or republished; the render completes at once |
| From Base only (or a partly antialiased frame) to either antialiasing mode | Kept AA colors are restored when the coloring is unchanged; any remaining AA tiles are applied in place over the visible base, reusing cached AA samples, so only pixels never antialiased need new subsamples |

Antialiased display hides base until AA tiles arrive, except mode switches keep
already-visible base to avoid blanking. Pan/zoom/resize use normal frame reuse
and the selected mode. Both scene modes persist through Undo/Redo, bookmarks,
shared presets/restore; unlike the old transient Deep Zoom Antialiasing switch,
leaving deep zoom does not clear its mode. PNG export keeps independent adaptive AA.

Known limitation: for Histogram Coloring and Orbit Trap there are no cached AA
samples, so finished AA colors survive only on the visible frame. A pan made
while Base only is shown drops them; switching back to an antialiasing mode
calculates AA for the new frame again. Smooth palettes keep their cached samples
across pans.

### P2.6 acceptance

The owner accepted P2.6 on 2026-10-27. Candidate: `main` at `b9af941`, which
contains PR #26 (Settings window and modes) and PR #31 (Settings sheet focus
fix). Environment: Apple Silicon, macOS 27.0.1, Maven JDK 26.0.1, JavaFX 26.0.2;
graphical checks ran in an active desktop session.

Manual owner checks on the running application, in light and dark appearance:

| Area | Result |
| --- | --- |
| Settings window | Opens as a sheet from File → Settings… (⌘,) with the current scene's modes; Return applies and Undo restores; Escape and Cancel discard the draft; Reset to Defaults changes only the draft; opening during a render works and Apply restarts it; a repeated ⌘, opens no second window; text and pop-up buttons are laid out without clipping. |
| Keyboard focus | With macOS Keyboard navigation on, Standard zoom is focused and Tab moves between the pop-up buttons and buttons. With it off, no control is focused and Tab does nothing. Clicking a hint never takes focus. |
| Render modes | In every mode the badge shows `Rendering N%`, rising to `Complete …`, with no `Refining…`. Base only completes without an AA pass and removes the loading screen. On a completed frame, switching between all three modes changes the image in place, and finished AA reappears at once. Switching during the base render and during the AA pass shows no stale or mixed pixels. |
| Deep zoom | The same mode checks passed for Mandelbrot and Julia deeper than about `1e-14`. |
| Responsiveness and persistence | Switching a large frame to Base only causes no noticeable stall. Relaunch restores both modes; bookmarks and copied presets carry them. |

The first manual pass found two keyboard-focus defects in the native sheet:
selectable labels became Tab stops, and the first pop-up button kept a focus ring
while Keyboard navigation was off. PR #31 fixed both with native regressions,
and the owner rechecked both Keyboard navigation settings.

Review: the PR #26 candidate returned one Minor finding, the Settings sheet
focus defect, and the PR #31 fix review was clean. The owner accepted this chain
as review closure for P2.6.

Independent verification on `main` passed: `./mvnw clean test` reported 596 tests
with zero failures and 128 opt-in skips; the native JavaFX suites passed 99
tests with two opt-in fullscreen skips; the JavaFX fallback suite without the
native bridge passed 41 tests with one native-only skip. Publication and
secret checks (Gitleaks 8.30.1) were clean.

The owner accepted the Histogram Coloring and Orbit Trap limitation described
above. Not verified: Windows, Linux, Intel Mac and the opt-in fullscreen tests.

### P2.1 acceptance

Acceptance on 2026-10-12 reconciled the open roadmap checkboxes with the implementation;
no production behavior was changed. Source base:
`3fdc50d71bdb2f654dd7e092821959d5ed93d376`, with additional menu-to-frame tests.
Environment: Apple Silicon, macOS 27.0.1, Maven JDK 26.0.2, JavaFX 26.0.2; the graphical
checks ran in an active desktop session.

| Requirement | Current acceptance evidence |
| --- | --- |
| Iteration editing | Both values remain drafts until Apply. Native and JavaFX forms cover reset, cancellation and invalid input; the menu regression waits for a completed frame with the new iteration settings, checks its actual iteration limit and the badge tooltip. |
| Julia editing | One dialog edits both finite components, with Reset to Defaults and Cancel. The menu regression applies a custom constant and compares every completed base-frame sample's iterations, escape flag and smooth value with a direct CPU calculation. Invalid input followed by Cancel preserves the scene and completed frame. |
| Zoom and effective iterations | Badge tests check compact large values and compare the post-zoom tooltip with the completed render request. Manual keyboard zoom changed the displayed scale and iteration budget; Home returned to the initial view. |
| Persistence and formula identity | Portable scene/JSON and render-job tests cover serialization, legacy defaults and parameter-sensitive formula identity. Closing and relaunching the current worktree with `mvn javafx:run` restored iteration values `420`/`70` and Julia values `-0.4`/`0.6`, confirmed in both editors and the first completed frame's badge. |

Checks executed:

```sh
mvn -Dtest=IterationSettingsTest,FractalSceneTest,FractalSceneJsonTest,AdaptiveIterationPolicyTest,RenderValueFormatTest test
mvn -Dfractal.fx.tests=true -Djavafx.cachedir=/tmp/fractallens-p21-final-cache \
    -DreuseForks=false \
    '-Dtest=FractalDialogsFxTest,MainMenuBarFxTest,MacIterationSheetFxTest,FractalResizeFxTest#renderBadgeShowsProgressAndLatestOutcomeWithoutHover+renderBadgeTooltipTracksTheRenderedSceneAfterZoom+canvasKeyboardNavigationUsesCameraAndIgnoresModifiedKeys' test
mvn test
mvn javafx:run
```

The targeted portable run passed 31 tests. The final graphical/native run passed 21
tests without skips. The full portable run reported 503 tests: 431 executed, 72 opt-in
skips, no failures/errors. JavaFX test forks emitted the existing native-access warning
for `javafx.graphics`; it did not prevent these checks.

Manual checks covered initial selected-field focus, keyboard Enter, validation
without losing drafts, Apply, Reset, Cancel/Escape, return to canvas keyboard
navigation, visible completion and restart. Light/dark JavaFX form snapshots and
native inherited-appearance tests passed; the live desktop used dark appearance.
The original user settings were restored after the checks. Reports and snapshots
remain ignored local output under `target/`, not published evidence.

Windows, Linux and Intel Mac runtime behavior was not exercised in this
acceptance; the shared JavaFX forms were exercised directly on macOS. GPU
hardware checks and multi-display/native-sheet expansion are separate roadmap
work and were not rerun for this test/documentation change.

### JavaFX dialog appearance

Coordinate and palette editors, Help, errors and export-completion messages now
share a system-font stylesheet, light/dark backgrounds and inputs, and the JavaFX
platform's system accent color. Appearance updates while a dialog is open;
preference listeners are removed when it closes. Buttons use consistent spacing
with the primary action on the right. Enter confirms, Escape cancels, and the
initial editable value is selected. Closing restores the owner's previous focus
when the owner is active.

Iteration settings use the same appearance and edit both values in one window:
base iterations and the additional iterations per zoom level are applied
together only after Apply. Reset to Defaults restores both fields in the open
dialog, and Cancel leaves the scene unchanged.

Dark Mode HIG audit (2026-10-04), against Apple's
[Dark Mode guidance](https://developer.apple.com/design/human-interface-guidelines/dark-mode):
the dialogs follow the system appearance and accent, and do not expose an
application-specific theme setting. The 13 active-desktop JavaFX tests passed
without skips. Reviewed content snapshots showed readable light/dark forms and
validation messages without overlapping actions. The palette menu regression
now waits for the requested coloring in the completed render, rather than only
for the synchronous scene-setting update.

This is partial alignment, not full HIG compliance. Background, input, label, border and
error colors are custom light/dark values, rather than AppKit semantic colors. Text
fields and buttons remain JavaFX controls. Native desktop tinting is not implemented for
these opaque backgrounds. Increase Contrast and Reduce Transparency, separately and
together, and a live system appearance change in the running application were not
manually verified in this audit. DialogPane snapshots do not prove native window-frame
behavior. Native sheets remain an open roadmap item.

Calculated contrast for normal states is 14.94:1 / 13.00:1 for body text,
5.77:1 / 7.58:1 for hints and 6.00:1 / 7.17:1 for errors (light / dark).
These exceed Apple's 4.5:1 minimum; the light hints and errors fall short of
the suggested 7:1 for custom small text. The accent-label luminance rule chooses
the higher-contrast black or white label for the current accent; hover, pressed,
disabled and all system accent variants were not exhaustively measured.

Coordinate validation retains exact decimal input, highlights the invalid field
and focuses it without closing the dialog. Palette Apply commits the current
typed positions even without a preceding Enter; invalid or out-of-range drafts
remain visible and cannot be applied. Inline errors clear after correction.
Horizontal padding belongs to content rather than the DialogPane itself so
wrapped validation text is measured correctly and stays clear of the buttons.

Validation on macOS (2026-09-11): three graphical regression tests passed without skips,
covering light/dark appearance, system accent, button order, initial and restored focus,
exact coordinates, invalid palette drafts, Apply and Escape. Snapshots for both editors,
Help and alerts were visually reviewed and are saved in `target/dialog-qa/`, alongside
the graphical test report. Manual keyboard checks also confirmed coordinate validation
with Return and correction with Tab.

```sh
mvn -q -Dfractal.fx.tests=true -Dtest=FractalDialogsFxTest -Djavafx.cachedir=/tmp/fractallens-javafx-cache test
mvn -q package
```

The package build passed: 408 tests, no failures/errors, 45 opt-in tests skipped. These
are owner-associated JavaFX WINDOW_MODAL dialogs. The roadmap separately tracks reduced
modality and native AppKit sheets; this appearance step does not implement those later
changes. Windows runtime verification remains outstanding.

### Native iteration settings sheet

On macOS, Edit Iteration Settings now opens a real owner-associated AppKit
`NSPanel` sheet with two `NSTextField` controls, Reset to Defaults, Cancel and
Apply. The native window background and secondary text use semantic `NSColor`
values; the other controls retain AppKit's default appearance. No application
appearance override is installed. The sheet inherits the native appearance and
keeps input drafts during validation and appearance changes.

Presentation uses `beginSheet:completionHandler:` rather than a blocking native
modal loop. Results are dispatched to JavaFX with `Platform.runLater`; only
Apply changes the scene, and only while the owner remains visible. Owner closure
disposes the session, unregisters its listener and suppresses pending results.
Repeated opening while a sheet is active leaves that sheet in place. Native
creation failures and non-macOS platforms retain the shared JavaFX form.

The native menu and iteration sheet share one JNI-backed window-handle resolver.
It accesses the internal JavaFX Glass peer inside the native bridge, avoiding
duplicate cross-module Java reflection. Missing peers and zero handles fail
before native presentation; JavaFX upgrades still require bridge validation.

Active-desktop checks on Apple Silicon covered applying both values, reset and cancel,
invalid drafts including integer overflow, and owner closure/disposal before a queued
Apply result. A separate appearance test verifies inherited Aqua and Dark Aqua from the
native owner without modifying system preferences. Forcing a high-contrast appearance
alone was normalized by macOS to ordinary Aqua without the global accessibility setting,
so that experiment does not verify Increase Contrast. Manual checks in the current
application confirmed the dark sheet layout, initial selection, Tab, Return, Escape,
Reset and Apply, including opening and cancelling in full screen and returning to
windowed mode. Light/high-contrast layout, live OS preference changes, Increase
Contrast, Reduce Transparency, multiple displays, VoiceOver, Intel macOS and the Windows
and Linux fallback runtime are unverified. Other dialogs were still JavaFX at this
validation point; this first native form did not establish application-wide HIG
compliance.

Final validation on 2026-10-04: the portable suite reported 500 tests, no
failures/errors and 69 opt-in skips. The targeted desktop suite reported 18 tests, no
failures/errors and one skip (the separately gated native context menu full-screen
test). Sheet full-screen behavior was checked manually as described above.

```sh
mvn -Dfractal.fx.tests=true "-Djavafx.cachedir=${TEMP_DIR}/fractallens-sheet-cache" \
  -DreuseForks=false -Dtest=MainMenuBarFxTest,FractalDialogsFxTest,MacIterationSheetFxTest,MacContextMenuFxTest test
```

### Native bookmark name sheet

Bookmarks → Add Bookmark uses the shared asynchronous AppKit `NSPanel` bridge
with a single native name field, Save and Cancel. There is no Reset action.
The field receives initial focus; Return saves and Escape cancels. Invalid
drafts stay editable with an inline error using a limit of 80 characters
(Unicode code points), including non-ASCII names and emoji. Validation is
shared with the JavaFX fallback and bookmark storage. The sheet inherits the
owner's native appearance and suppresses queued Save callbacks after owner
closure. It cannot overlap Iteration Settings or Julia Parameters sheets.
The JavaFX dialog is retained on other platforms and on native bridge failure.

Developer regression command (active macOS desktop required):

```shell
mvn -Dfractal.fx.tests=true "-Djavafx.cachedir=${TEMP_DIR}/fractallens-bookmark-sheet" -DreuseForks=false -Dtest=MacBookmarkSheetFxTest,MacIterationSheetFxTest,MacJuliaSheetFxTest,MainMenuBarFxTest,FractalDialogsFxTest,MacSystemMenuFxTest test
```

Developer checks on 2026-10-14 used macOS 27.0.1, Apple Silicon and JavaFX 26.0.2.
The graphical command passed 40 tests without skips, covering native Save and
Cancel, invalid-draft retention, inherited Aqua/Dark Aqua, owner closure,
duplicate opening, editor overlap and native menu availability. `mvn clean test`
passed 442 executed tests with 92 opt-in skips. An isolated source copy built
without the `macos-native-menu` profile passed seven JavaFX tests, including
the actual Add Bookmark fallback, persistence workflow and character validation.
Storage regressions cover 80 ASCII, Cyrillic and supplementary Unicode characters,
replacement, restart/readback, deletion and legacy single-file compatibility.

A separate temporary-profile application launch visibly exposed an AppKit
accessibility `sheet` with one focused name field and Save/Cancel. Empty Return
showed the character-based error; correcting the field to a name containing
an emoji and pressing Return saved it and immediately enabled Restore/Delete.
Repeated opening, Cancel and Escape left no extra bookmarks. The live desktop
used dark appearance; light layout, Intel macOS, Windows/Linux desktop,
fullscreen and multiple displays were not visually checked for this sheet.

### Native Julia parameters sheet

On macOS, Edit Julia Parameters opens an owner-associated AppKit `NSPanel`
sheet using the same native presentation and lifecycle as Iteration Settings.
`MacParameterSheet` and `FractalParameterSheet.m` share the native controls,
semantic colors, asynchronous completion and owner-closure disposal. The
editors retain separate value types, defaults and validation rules.

Julia's Real and Imaginary fields start with the current scene constant.
Reset to Defaults changes both drafts to `-0.8` and `0.156` without applying
or closing. Apply validates both components with Java's finite-double parsing
contract before atomically updating the scene on the JavaFX thread. Empty,
malformed, NaN, infinite and overflowing values keep the sheet open with an
inline error and the invalid field selected. Return applies; Escape and Cancel
leave the scene unchanged. Closing the owner or disposing a session suppresses
queued Apply results. Reopening an active editor keeps the existing sheet;
opening the other parameter editor is ignored until that sheet closes.
JavaFX remains the fallback on other platforms or if native presentation fails.

At the P2.1 validation point, coordinate/palette editors, Help and application
alerts still required native conversion or removal of unnecessary modality.
The later P2.4 implementation and its acceptance are described below.

Validation on 2026-10-12 used macOS 27.0.1 on Apple Silicon, JavaFX 26.0.2,
Maven JDK 26.0.2 (Java release target 25), and desktop launcher JDK 25
(update 4, patch 1):

- `mvn clean test` passed with 507 tests, no failures/errors and 76 opt-in
  skips. The final `mvn test` after the overlapping-editor guard passed with
  the same counts.
- The active-desktop suite below passed 22 tests with no failures/errors/skips.
  It covers both native editors, inherited Aqua/Dark Aqua, finite Julia input,
  reset/cancel, queued-result suppression, duplicate/cross-editor opening,
  JavaFX forms, and exact completed-frame Julia samples against a CPU reference.
- An isolated source copy with the `macos-native-menu` profile disabled passed
  all 15 `MainMenuBarFxTest` and `FractalDialogsFxTest` tests. The missing native
  library warning was expected; this checks fallback on macOS rather than
  Windows/Linux runtime behavior.
- A separate temporary development bundle running the candidate application
  and a temporary session store confirmed the dark native layout, initial
  selection, Tab, Return, inline NaN validation/correction, Reset followed by
  Apply or Cancel, Escape, full-screen opening/cancellation and return to the
  window. Applied values were confirmed by reopening the editor and by the
  temporary saved scene. The user's open application/session was preserved.
  The first custom launch omitted module exports needed for application-menu
  naming; repeating with the configured exports removed those Java exceptions.
  macOS emitted a sandbox-extension diagnostic for the temporary bundle, without
  preventing the checked interactions.

```sh
mvn -Dfractal.fx.tests=true "-Djavafx.cachedir=${TEMP_DIR}/fractallens-native-julia-cache" \
  -DreuseForks=false \
  -Dtest=MacJuliaSheetFxTest,MacIterationSheetFxTest,MainMenuBarFxTest,FractalDialogsFxTest test
```

Aqua/Dark Aqua inheritance and draft preservation were checked automatically; light
layout was not visually inspected. Live OS preference changes, Increase Contrast, Reduce
Transparency, VoiceOver, multiple displays, Intel macOS, and Windows/Linux fallback
execution are unverified.

### Native windows (P2.4)

The production modal-entry audit now routes all application forms and actionable
messages through AppKit when the bridge is available:

| Entry point | macOS presentation | Fallback |
| --- | --- | --- |
| Iteration Settings / Julia Parameters / Add Bookmark | Existing native field sheets | JavaFX owner-modal forms |
| Settings (P2.6) | Native sheet with labelled pop-up buttons | JavaFX owner-modal form with combo boxes |
| Go to Coordinates | Native two-field sheet; exact decimal parsing, selectable hint with the compact badge zoom (`Zoom: ≈1.23 × 10^30`) and deep-zoom state | JavaFX owner-modal form |
| Export PNG dimensions | Native two-field sheet with the shared memory-budget validation and live size estimate | JavaFX owner-modal form |
| Render/export/bookmark/preset errors | Asynchronous `NSAlert` sheet | JavaFX owner-modal alert |
| Close while exporting (including system Quit) | Asynchronous `NSAlert` decision; Return/Escape defaults to returning | JavaFX owner-modal decision |
| Edit Palette | Nonmodal native panel with position fields, color wells, add/remove and live gradient preview | Nonmodal JavaFX editor with the same preview |
| Help | Nonmodal native scrollable text panel | Nonmodal JavaFX Help window |
| PNG destination / overwrite decision | JavaFX `FileChooser` system save panel | Platform system save panel |
| Export progress / completion | Existing in-window status | Same |

The palette preview shows the draft gradient using the same OKLab implementation
as the renderer. It changes as valid positions or colors change; invalid drafts
remain editable and retain the last valid preview. Apply commits the palette to
the canvas once, preserving scene-history behavior; Cancel, Escape and window
closure discard drafts. The native editor supports alpha and retains at least
two stops. Palette and Help do not prevent canvas navigation or other editors.
Repeated opening keeps the existing window. Owner shutdown closes these windows.

Native callbacks dispatch application actions with `Platform.runLater`. Closing
the owner or disposing a session suppresses already queued results. The shared
owner registry prevents overlapping native modal editors; errors and close
confirmations wait for the current sheet instead of falling back into a second
modal window. Native panels inherit system appearance, use semantic colors and
restore the previous JavaFX focus when the owner becomes active. JavaFX remains
the fallback on bridge failure and on Windows/Linux. No native modal run loop or
worker wait is introduced on the JavaFX thread.

Developer validation commands:

```shell
mvn clean test
mvn -Dfractal.fx.tests=true "-Djavafx.cachedir=${TEMP_DIR}/fractallens-p24-fx" \
  -DreuseForks=false \
  -Dtest=MacModalWindowsFxTest,FractalResizeFxTest,MacBookmarkSheetFxTest,MacIterationSheetFxTest,MacJuliaSheetFxTest,FractalDialogsFxTest,MainMenuBarFxTest test
```

For fallback routing, copy the source tree and `pom.xml` to an isolated directory
without generated classes, then run there:

```shell
mvn -P'!macos-native-menu' -Dfractal.fx.tests=true -Dfractal.fallback.tests=true \
  "-Djavafx.cachedir=${TEMP_DIR}/fractallens-p24-fallback-cache" -DreuseForks=false \
  -Dtest=DialogRoutingFallbackFxTest,FractalResizeFxTest,FractalDialogsFxTest,MainMenuBarFxTest test
```

Developer desktop inspection on 2026-10-15 used an isolated temporary application
profile on Apple Silicon. Dark native coordinate, palette, export-size and Help
layouts were inspected. Initial selection, invalid coordinate/size drafts,
coordinate correction with Tab/Return, gradient-preview changes, and Escape
cancellation were checked. The application completed its initial CPU render.
This was scoped developer inspection, not independent verification or full manual
acceptance. The tests cover exact coordinate application, export bounds,
alpha-preserving palette apply/add/remove, queued alerts, close decisions,
owner-closure suppression and existing native forms/fallbacks.

The final developer checks passed: `mvn clean test` and `mvn test` each reported
560 tests (454 executed, 106 opt-in skips, no failures/errors); the six-class
native desktop suite passed 46 tests without skips. The isolated fallback suite
reported 34 tests (33 executed, one native-only overlap test skipped, no failures/
errors); that overlap scenario passed in the native run. The fallback-routing
class explicitly asserts that the bridge is absent. Existing JavaFX native-access
warnings and the deliberately missing-library fallback warning were retained.

Repair-round developer validation on 2026-10-15 explicitly covers translucent
preview pixels and the existing render/recolor error and export-close workflows.
The preview bitmap declares nonpremultiplied alpha to match the straight ARGB
supplied by the palette. An AppKit color readback regression for `0x20804020`
failed on an isolated copy with the original bitmap format (red read as `1.0`
instead of `128/255`) and passed with the fix. Error tests inspect native alert
text or the JavaFX fallback and retain the failed-status/completed-frame checks.
Export-close tests wait for each asynchronous answer and hold the export
coordinator at a deterministic gate until confirmed closure, then check executor
drain and absence of the cancelled output.

The repair-round `mvn clean test` passed 560 tests (454 executed, 106 opt-in
skips). `MacModalWindowsFxTest,FractalResizeFxTest` passed 31 executed tests;
`DialogRoutingFallbackFxTest,FractalResizeFxTest` in an isolated build without the
native bridge passed 29 executed tests. Both graphical runs skipped only the two
separately gated fullscreen tests. No additional manual UI acceptance was
performed in this repair round; these checks are developer self-validation.

Full manual acceptance remains pending: light layout, live OS appearance and
accessibility changes, VoiceOver, all focus/keyboard combinations, native color panel
interactions, full-screen transitions, and mixed-DPI/negative-origin/display- edge
context menus on multiple physical displays. Windows/Linux and Intel Mac runtime checks
are unverified; the universal native library is compiled for both macOS architectures.
These gaps were closed by the [P2.4 acceptance](#p24-acceptance) on 2026-10-27.

The export-close Escape repair preserves Return on the safe default button.
An `NSButton` has one key equivalent, and setting the default button cell restores
Return, so assigning Escape to that same button cannot support both keys.
A local AppKit key monitor routes unmodified Escape only for the confirmation's
own window to **No, Return**. The monitor uses a weak session reference and is
removed on either completion or disposal, including owner closure. Keyboard
regressions dispatch native `NSEvent` key-down events through `NSApplication`
and check the false result, sheet cleanup and continued owner visibility.

Escape-repair developer checks on 2026-10-15 passed `mvn clean test` (561 reported, 454
executed, 107 opt-in skips) and the seven-class native desktop command above with
`-Dfractal.fx.fullscreen=true` (77 executed, no skips). Both runs had no
failures/errors. The final keyboard regression failed for Escape, while Return passed,
on an isolated build with the pre-repair native source; both keys passed with the
repair. The universal arm64/x86_64 bridge compiled with warnings treated as errors. In
the running application with a temporary profile, a 4000×4000 Julia export continued
after Escape dismissed its close confirmation and after Return dismissed a freshly
reopened confirmation. The PNG finished successfully and the application then closed
normally. These are developer self-validation results; fresh independent review and
verification remain required for the changed candidate. The broader P2.4 acceptance gaps
above were open at that time; fallback runtime checks were not repeated for this
native-only repair.

The JavaFX palette-preview repair on 2026-10-15 routes color, add/remove and position
edits through the existing validation callback. Only a fully valid draft reaches the
preview, using `previewStops()` rather than committed spinner values. A regression
compares every preview pixel across invalid text and color changes, add/remove,
correction and a subsequent color change before Apply. It failed against the incoming
implementation and passed after the repair. Developer checks passed `mvn clean test`
(562 reported, 454 executed, 108 opt-in skips), `FractalDialogsFxTest` in the active
desktop (7 executed, no skips), and an isolated build without the native bridge running
`DialogRoutingFallbackFxTest,FractalDialogsFxTest,MainMenuBarFxTest` (35 reported, 34
executed, one native-only overlap skip). All final runs had no failures/errors; the
expected missing-library fallback warnings were retained. Visible fallback inspection
checked invalid-text retention, Apply rejection, clearing the error and updating the
gradient after correction, and Escape cancellation. Color-change preview correctness was
checked by the pixel regression; selecting a color through the live JavaFX popup was not
established during this inspection. No Windows/Linux or Intel Mac runtime acceptance was
added. Fresh independent review and verification remain required for this changed
candidate.

### P2.4 acceptance

The owner accepted P2.4 on 2026-10-27. Manual acceptance ran on Apple Silicon
macOS against `main` at `02a1180` in an isolated application profile, with the
built-in Retina display and an external 1x display (LG FHD) placed both to the
right of it and to its left (negative origin). Most steps were driven through
desktop automation; the owner checked the remaining steps by hand.

Passed:

- Go to Coordinates: initial selection, an invalid draft kept on Return,
  correction with Tab and Return, Escape.
- Edit Palette: nonmodal window, system color panel with alpha, add and remove
  stops, Apply, Undo after Apply, Cancel.
- Help: a single window on repeated opening, Escape.
- Native `NSAlert` sheet for an invalid clipboard preset.
- Full screen with an open sheet or palette window, and its exit.
- Live switching to light appearance, Increase Contrast, Reduce Transparency
  and VoiceOver (owner).
- Go to Coordinates, palette, color panel and Help on the external display
  with a negative origin (owner).
- Canvas context menu on both displays, away from the bottom corners described
  below.

Acceptance found two Minor display defects, both fixed in PR #35: a new palette
stop showed a rounded midpoint such as `0.5249999999999999`, and the Go to
Coordinates hint showed the zoom with dozens of digits after wheel zoom.

Accepted platform limitation: when a window fills a display that sits next to
a taller display with a different bottom edge, a right-click within a few
points of the window's bottom corner nearest the outer edge makes AppKit place
the menu off that display. With the external display on the right, the menu is
clipped at its right edge (within about 5–12 pt of the bottom-left corner);
with it on the left in full screen, the menu flashes and disappears (within
about 45 pt of the bottom-right corner). A pure AppKit probe reproduces both
cases with `popUpMenuPositioningItem:` and `popUpContextMenu:`, and the menu
opens normally about 40 pt from the corner and in the middle of the edge. The
Java anchor coordinates were verified to be correct, so FractalLens does not
work around this AppKit placement.

Observation: a right-click on an inactive window only activates it, as for
other AppKit windows.

Independent review and verification were not separate workflow stages for this
acceptance. Windows, Linux and Intel Mac are covered only by the portable CI suite.
