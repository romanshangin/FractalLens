# macOS interface

The main window contains only the fractal canvas and the standard window frame.
`MainMenuBar` uses JavaFX's system menu integration: on macOS the menus live in
the menu bar at the top of the display. Other platforms retain an in-window menu
bar. The rendering pipeline, precision limits, and pointer gestures are unchanged.

The main window also registers its commands as JavaFX's application-default
system menu. Glass otherwise replaces them with an empty default when the stage
loses focus, leaving only the application name during a macOS Spaces activation
gap. The commands remain installed across Space and application switches without
requiring pointer movement over the menu bar. Closing the view clears the default
registration so it does not retain the closed view's commands.

Run the native menu regression in an active macOS desktop session:

```shell
mvn -Dfractal.fx.tests=true "-Djavafx.cachedir=${TEMP_DIR}/fractallens-system-menu" -DreuseForks=false -Dtest=MacSystemMenuFxTest test
```

The regression inspects the AppKit menu before focus loss, while an unowned
window without menus has focus, after focus returns, and after view disposal.
It also gates the CPU renderer to inspect every Color and Render command in both
JavaFX and AppKit during startup, after completion, during another render, and
after its completion. An AppKit menu update during the first render reproduces
validation of an opened menu; assertions after completion do not refresh the
native menu or supply pointer input.


2026-10-03 render-availability validation on macOS 27.0 arm64, JDK 26.0.1,
and JavaFX 26.0.2: the regression reproduced a native-disabled Edit Palette item
after completion on the original implementation. After the fix, both native
menu tests and all five MainMenuBar tests passed without skips. `mvn test` passed
with 400 executed tests and 63 opt-in skips. In the current development launch,
Color and Render were opened during separate Julia renders: native accessibility
reported disabled actions during calculation and enabled actions in the same
open menus after completion, without pointer input over those actions. Deep Zoom
Antialiasing remained unavailable in standard mode, and the palette editor opened
with its shortcut and was cancelled. The UI tool could not capture a screenshot,
so the actual painted enabled/disabled appearance remains visually unverified.
Windows and Linux runtime checks were not run for this change.

For visible acceptance, switch to another Space, activate another application,
then return to FractalLens without moving the pointer onto the menu bar and check
that File through Help remain visible and usable.

Validation on 2026-09-29: the native regression failed on the original code
because all nine command menus disappeared on focus loss, then passed after
registration was added (1 test, no skips). `mvn test` passed with 399 executed
tests and 60 opt-in skips. The user confirmed the original Spaces sequence and
opening Help in the current development build; the running process was checked
to distinguish it from an older packaged copy with the same bundle identifier.

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

The selected Mandelbrot icon is bundled in
`src/main/resources/com/shangin/fractal/app/icons/fractallens.png` (1024×1024,
RGBA). `ApplicationIcon` loads it from the application resources at startup.
On macOS the Maven profile and shared IntelliJ run configuration supply
`-Xdock:icon` with the absolute path to `FractalLens.icns`. The native launcher
therefore has the correct icon before JavaFX creates the application; setting
it only in `Application.start()` caused the generic Java icon to flash first.
`ApplicationIcon` retains its public `Taskbar` API fallback for custom launchers.
Other platforms use `Stage.getIcons()`.
macOS does not receive a window document-proxy icon. Restart the application
to see the change.

For a macOS application bundle, pass these additional `jpackage` options:

```text
--name FractalLens
--icon src/main/resources/com/shangin/fractal/app/icons/FractalLens.icns
```

The ICNS contains standard and Retina representations up to 1024 pixels.
The matte revision removes painted highlights, glow, gradients, and bevels.
Its unmasked square artwork and native Icon Composer source are in
`design/app-icon/`; the original glossy concept remains in `design/icon-concepts/`.
To rebuild PNG, ICNS, and the foreground layer, run `python3 scripts/build_app_icon.py`
with Pillow installed. The compatibility mask is derived from Apple's native
export and is applied only to the flattened Java/ICNS assets, not the native layers.
See `design/app-icon/README.md` for native compilation and packaging instructions.

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

Full screen and ordinary window resizing share the same behavior. At the preset's
initial view, the last completed image stretches immediately across the entire
canvas. A fitted frame starts rendering after the window size has remained stable
for 75 ms, then replaces the preview atomically when ready. Intermediate resize
tiles stay hidden so that
different aspect ratios cannot produce side bands or split fractal contours.
In a zoomed view, the center and render-pixel
spacing stay fixed: expansion reuses the center (including AA) and computes only
the exposed borders; shrinking a completed frame crops it without rendering.
The next pan keeps the resized frame's iteration limit, so dragging after either
operation also calculates only the newly exposed edge strips.
Render dimensions use equal parity to keep centered copies on integer pixels.
If resizing interrupts AA publication, missing display tiles are completed even
when their sample data is already cached.

The native JavaFX regression checks can be run with
`mvn -Dfractal.fx.tests=true -Dtest=FractalResizeFxTest test` on a desktop session.

| Menu | Commands |
| --- | --- |
| File | Export PNG… (⌘⇧E), Close Window (⌘W) |
| Edit | Standard text editing commands for focused text fields |
| View | Zoom In (⌘+, also ⌘=), Zoom Out (⌘−), Reset View (⌘0), Go to Coordinates… (⌘L), Enter/Exit Full Screen (⌃⌘F) |
| Fractal | Mandelbrot, Julia, Multibrot, Burning Ship, Tricorn |
| Color | Palette presets, Edit Palette… (⌘⇧P), Orbit Trap, Histogram Coloring, Animate Palette |
| Render | Display Quality, Antialiasing Pattern, Edit Iteration Settings…, Edit Julia Parameters…, Deep Zoom Antialiasing |
| Window | Minimize (⌘M), Zoom |
| Help | FractalLens Help, including pointer and keyboard navigation |

Edit Iteration Settings is available for every fractal when rendering is idle.
Edit Julia Parameters is enabled only for Julia.

On other platforms the platform shortcut modifier replaces Command.
Arrow keys pan the focused canvas after zooming in. Page Up and Page Down zoom
around the center, and Home resets the view. Pointer-centered scrolling, dragging, and
trackpad gestures remain available. Menu and keyboard zoom preserve the center.
Right-clicking the canvas opens a single **Copy Coordinates and Zoom** command.
It copies the current center's real and imaginary coordinates and zoom together
as plain text, preserving decimal precision for sharing a view.
`FractalContextMenu` owns the commands and lifecycle separately from the renderer.
On macOS it opens a real AppKit `NSMenu` through the small Objective-C/JNI bridge
in `src/main/macos/FractalContextMenu.m`. AppKit controls appearance, highlighting,
keyboard navigation and tracking. No CSS or custom appearance is applied to it.
The existing styled JavaFX popup remains available on other platforms and when
the native library or the required JavaFX module access is unavailable.

Each native menu session is created and cancelled on the JavaFX/AppKit main
thread. Opening is scheduled in the native run loop so the Java call returns
before AppKit starts tracking; selection is delivered with `Platform.runLater`
after tracking finishes. Closing or replacing a session suppresses its pending
callback and releases its JNI global reference. Window movement, resizing, focus
loss, scene detachment and canvas commands cancel the session. Input consumed by
AppKit follows the system menu's behavior; JavaFX input that reaches the canvas
continues through the existing dismissal filter.

Opening is scheduled in the default run-loop mode so a replacement waits for
the previous menu's tracking loop to unwind. Scheduling it in common modes can
prematurely open and cancel the replacement. The item's target/action explicitly
ends tracking, including when accessibility activates it directly. Both cases
are covered by the native regression suite.

Positioning uses the owning window's content view in logical points, including
its flipped-coordinate convention. There is no manual Retina multiplication or
conversion through the primary screen's height. AppKit positions the popup
within the current display's available area.

The `macos-native-menu` Maven profile compiles a universal `arm64`/`x86_64`
`libfractal-menu.dylib` during `process-classes` and includes it in the application
JAR. Both `mvn javafx:run` and `mvn package` run this phase. Building requires the
macOS SDK and Xcode Command Line Tools; running the packaged library does not.
A direct IDE launch should first run `mvn process-classes` so the library exists
in the output directory. Besides native access, custom launchers need:

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

Coordinates and custom gradients use owner-associated dialogs with explicit
Go/Apply and Cancel actions. Coordinate validation keeps invalid input visible
and explains how to fix it. The coordinate dialog also exposes a copyable zoom
value and the current deep-zoom status. Palette edits are drafts until Apply;
Cancel leaves the current palette unchanged. Editing a preset clears its menu
checkmark to indicate a custom gradient.

Color and Render remain openable during startup and later renders. Their action
items, including palette, orbit-trap, quality and sampling choices, are explicitly
disabled while the renderer is busy. Completion updates every action's native
state even when a menu was opened during rendering; hovering or reopening is not
required. Disabling only the parent menu lets AppKit validation leave child items
inactive after the JavaFX parent is re-enabled.

Deep zoom antialiasing is available only in deep zoom. Palette animation is unavailable
when histogram coloring or an orbit trap is active; its checkmark clears when
navigation or a scene change stops animation. Export becomes available after
a completed frame exists, and is disabled while an export is running.

## Design references and implementation boundary

- [Designing for macOS](https://developer.apple.com/design/human-interface-guidelines/designing-for-macos/)
- [Menus](https://developer.apple.com/design/human-interface-guidelines/menus)
- [Going full screen](https://developer.apple.com/design/human-interface-guidelines/going-full-screen)
- [JavaFX MenuBar](https://openjfx.io/javadoc/26/javafx.controls/javafx/scene/control/MenuBar.html)

This is a JavaFX implementation with native macOS menus, window controls, and
file chooser. The coordinate and gradient editors use JavaFX controls, not
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

The native suite checks AppKit target/action, cancellation, suppression of stale
selection callbacks, scene detachment, window resize, repeated close and live
render publication during menu tracking. Its optional full-screen test visits
every attached display and enters/exits full screen. It uses a test-only FFM
probe to inspect the tracking session and invoke NSMenu actions; it does not
substitute a JavaFX popup or synthesize global keyboard input. The existing
JavaFX-menu test exercises the fallback directly. Run these classes in separate
processes (or use `-DreuseForks=false`) because they shut down JavaFX at the end.

2026-09-10 validation: macOS 26.6.2, Apple M3 Pro, JDK 26.0.2 and JavaFX 26.0.2,
using the built-in Liquid Retina XDR display (3456×2234). The three native tests
and the existing JavaFX fallback test passed in separate JVMs. This includes
actual frame completion and publication while NSMenu is tracking, window resize,
scene detachment, stale-selection suppression, immediate menu replacement,
native target/action, and full-screen entry/exit. The action probe no longer
issues a separate cancellation: the production action must finish tracking.

In the running application, right-click exposed a native accessibility `menu`
with the Objective-C `choose:` action. Escape, outside click and application
focus switching dismissed it. Keyboard selection and direct accessibility
activation both closed the menu and updated the clipboard. Reading the result
in a temporary coordinate-dialog field confirmed current exact coordinates and
`Zoom: 1.25` after zooming, and `Zoom: 1` after restarting at the initial view;
the test dialog was cancelled without applying the pasted text.

The Maven build packages both arm64 and x86_64 slices in the JAR. Native runtime
checks were performed on arm64; Intel and Windows runtime checks were not run.
Final `mvn package`: 405 tests, 0 failures/errors, 42 opt-in tests skipped.
The separate GUI run passed all four selected tests without skips; its reports
were retained in `target/native-menu-validation/`. `git diff --check` passed.
Only one physical display was attached. Multi-display/mixed-DPI placement and
negative screen origins remain a separate hardware-validation item in the
roadmap; the single-display test is not evidence for those cases.

Resize regression checks: `mvn -Dfractal.fx.tests=true -Dtest=FractalResizeFxTest test`.
Add `-Dfractal.fx.fullscreen=true` to exercise two native full-screen/window cycles
at approximately 650,000× and 426 billion× zoom. These opt-in checks open macOS
windows, verify full image coverage and preserved center pixels, and save snapshots
to `target/fullscreen-zoom-60-qa.png` and `target/fullscreen-zoom-120-qa.png`.
No-op scrolling and clicks must leave pending rendering intact.
In a zoomed resize, completed base-pass pixels at newly exposed edges are shown
immediately. Refined AA tiles replace those pixels in place instead of clearing
the base layer and revealing a stair-stepped transparent boundary. The reported
trackpad case is retained as an opt-in native regression fixture; it performs
eight separate zoom gestures and waits for a completed refined frame after each.

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

The matte revision was opened in Icon Composer and compiled by Xcode `actool`.
An actual JavaFX launch with `-Xdock:icon` read the native Dock image both before
and after `ApplicationIcon.install()`: both matched the Mandelbrot asset
(mean RGB difference 2.24/255 at 128×128, allowing native resampling). Maven's
forwarded JVM arguments also contained the correct absolute ICNS path. Compilation
and `git diff --check` passed. This check specifically covers the startup flash,
not only the icon after the application has initialized.

### Viewport mode indicator

A compact translucent badge sits 12 logical pixels from the bottom-left corner
of the viewport. A blue dot marks **Standard** rendering; a soft coral dot marks
**Deep Zoom**, following the controller's actual precision policy. The badge is
informational, with no click action, and is not included in PNG exports.

The badge shows the base-render percentage, refinement state, and latest viewport
render duration without requiring hover. The percentage counts exact ready pixels,
including samples reused from a previous frame. Hovering adds the mode description,
antialiasing details, current zoom, and the effective iteration limit. Zoom and
iteration values below one million are shown as whole numbers; larger values use
three significant digits in powers of ten, with an approximation mark when rounded.
The hover text does not repeat the render duration. Cancelled or failed work
never appears as a completed duration; current render and recolor failures show
an in-application error.
Enabling deep antialiasing after completion adds refinement time to the base
render duration without counting the intervening idle time. Palette animation
and export do not replace the viewport timing.

P1.1 validation on an active macOS 27.0 desktop: a Page Up render from a restored
deep Mandelbrot scene visibly advanced through 0%, 30%, and 68% before showing
`Complete 7.50 s`. The opt-in JavaFX regression starts from a completed frame,
injects a recolor failure on the worker path, and checks the error dialog's
message in the application window. This is functional evidence, not a render
performance measurement.

### JavaFX dialog appearance

Coordinate and palette editors, Help, errors and export-completion messages now
share a system-font stylesheet, light/dark backgrounds and inputs, and the JavaFX
platform's system accent color. Appearance updates while a dialog is open;
preference listeners are removed when it closes. Buttons use consistent spacing
with the primary action on the right. Enter confirms, Escape cancels, and the
initial editable value is selected. Closing restores the owner's previous focus
when the owner is active.

Coordinate validation retains exact decimal input, highlights the invalid field
and focuses it without closing the dialog. Palette Apply commits the current
typed positions even without a preceding Enter; invalid or out-of-range drafts
remain visible and cannot be applied. Inline errors clear after correction.
Horizontal padding belongs to content rather than the DialogPane itself so
wrapped validation text is measured correctly and stays clear of the buttons.

Validation on macOS (2026-09-11): three graphical regression tests passed with
no skips, covering light/dark appearance, system accent, button order, initial
and restored focus, exact coordinates, invalid palette drafts, Apply and Escape.
Snapshots for both editors, Help and alerts were visually reviewed and are saved
in `target/dialog-qa/`, alongside the graphical test report. Manual keyboard
checks also confirmed coordinate validation with Return and correction with Tab.

```sh
mvn -q -Dfractal.fx.tests=true -Dtest=FractalDialogsFxTest -Djavafx.cachedir=/tmp/fractallens-javafx-cache test
mvn -q package
```

The package build passed: 408 tests, zero failures/errors, 45 opt-in tests skipped.
These are owner-associated JavaFX WINDOW_MODAL dialogs. The roadmap separately
tracks reduced modality and native AppKit sheets; this appearance step does not
implement those later changes. Windows runtime verification remains outstanding.
