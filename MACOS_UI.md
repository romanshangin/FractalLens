# macOS interface

The main window contains only the fractal canvas and the standard window frame.
`MainMenuBar` uses JavaFX's system menu integration: on macOS the menus live in
the menu bar at the top of the display. Other platforms retain an in-window menu
bar. The rendering pipeline, precision limits, and pointer gestures are unchanged.

The system application menu is named **FractalUI**, including its Hide and Quit
commands. `-Xdock:name` alone does not reliably name this menu in JavaFX 26.
`MacApplicationMenu` updates the Glass application menu after installation and
when the main window regains focus. This small adapter isolates an internal
JavaFX API because the public MenuBar API cannot edit the application menu.

`mvn javafx:run` automatically enables the required module access on macOS.
IntelliJ users can select the shared **FractalUI (macOS)** run configuration.
Custom run configurations and packaged launchers need these VM options:

```text
-Xdock:name=FractalUI
--add-exports=javafx.graphics/com.sun.glass.ui=com.shangin.fractal
--add-opens=javafx.graphics/com.sun.glass.ui.mac=com.shangin.fractal
```

The name takes effect on restart. When packaging with `jpackage`, also use
`--name FractalUI`. Recheck the adapter when upgrading JavaFX.

## Application icon

The selected Mandelbrot icon is bundled in
`src/main/resources/com/shangin/fractal/app/icons/fractalui.png` (1024×1024,
RGBA). `ApplicationIcon` loads it from the application resources at startup.
On macOS the Maven profile and shared IntelliJ run configuration supply
`-Xdock:icon` with the absolute path to `FractalUI.icns`. The native launcher
therefore has the correct icon before JavaFX creates the application; setting
it only in `Application.start()` caused the generic Java icon to flash first.
`ApplicationIcon` retains its public `Taskbar` API fallback for custom launchers.
Other platforms use `Stage.getIcons()`.
macOS does not receive a window document-proxy icon. Restart the application
to see the change.

For a macOS application bundle, pass these additional `jpackage` options:

```text
--name FractalUI
--icon src/main/resources/com/shangin/fractal/app/icons/FractalUI.icns
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
"-Xdock:icon=/path/to/FractalUI/src/main/resources/com/shangin/fractal/app/icons/FractalUI.icns"
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
| Render | Display Quality, Antialiasing Pattern, Deep Zoom Antialiasing |
| Window | Minimize (⌘M), Zoom |
| Help | FractalUI Help, including pointer and keyboard navigation |

On other platforms the platform shortcut modifier replaces Command.
Arrow keys pan the focused canvas. Pointer-centered scrolling, dragging, and
trackpad gestures remain available. Menu and keyboard zoom preserve the center.
Right-clicking the canvas opens a single **Copy Coordinates and Zoom** command.
It copies the current center's real and imaginary coordinates and zoom together
as plain text, preserving decimal precision for sharing a view.
`FractalContextMenu` owns the popup commands and dismissal behavior separately
from the renderer. Its JavaFX popup uses macOS-style rounded corners, the system
font, appearance and accent color, a translucent background, an inset rounded
selection and a soft shadow. Transparency does not include backdrop blur; this
is a JavaFX popup, not an AppKit NSMenu. Styling lives in
`fractal-context-menu.css`. Canvas input, scene commands, window movement/resizing
and loss of focus dismiss the menu without swallowing the initiating canvas click.
Pointer movement alone leaves it open so its items remain reachable.

Coordinates and custom gradients use owner-associated dialogs with explicit
Go/Apply and Cancel actions. Coordinate validation keeps invalid input visible
and explains how to fix it. The coordinate dialog also exposes a copyable zoom
value and the current deep-zoom status. Palette edits are drafts until Apply;
Cancel leaves the current palette unchanged. Editing a preset clears its menu
checkmark to indicate a custom gradient.

Appearance commands are unavailable while the renderer is busy. Deep zoom
antialiasing is available only in deep zoom. Palette animation is unavailable
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
26.0.2. A test bundle deliberately named MenuNameCheck displayed **FractalUI**
in the actual native menu, with **Hide FractalUI** and **Quit FractalUI** items.

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

Hovering shows the mode description and the latest viewport render duration
(including enabled antialiasing). A new render replaces the previous timing with
`Rendering…`; cancelled or failed work never appears as a completed duration.
Enabling deep antialiasing after completion adds refinement time to the base
render duration without counting the intervening idle time. Palette animation
and export do not replace the viewport timing.
