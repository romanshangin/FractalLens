# FractalLens session format

FractalLens restores the most recently closed scene at the next normal launch.
The session contains only reproducible scene state; window dimensions, render
priority, in-progress work and transient animation state are not persisted.

## Storage locations

- macOS: `~/Library/Application Support/FractalLens/last-session.json`
- Windows: `%LOCALAPPDATA%\FractalLens\last-session.json`, falling back to
  `%APPDATA%` and then the user profile
- Linux: `$XDG_STATE_HOME/fractallens/last-session.json`, or
  `~/.local/state/fractallens/last-session.json`

The file is saved when the main view closes. A missing or malformed session
does not prevent startup: FractalLens tries the previous valid generation and
then falls back to the default Mandelbrot scene.

FractalLens uses a separate application-data directory from the earlier private
application. Existing session files are not moved or deleted automatically. To
reuse an earlier scene, copy its `last-session.json` into the new platform
location while both applications are closed. The scene schema is unchanged.

## Schema contract

`schemaVersion` is required. Version 2 is the current write format and records:

- the fractal preset;
- viewport center and scale as decimal strings;
- base and per-zoom iteration settings;
- Julia constant real and imaginary components (defaulting to -0.8 and 0.156
  when absent from older version 2 sessions);
- palette identity, editable ARGB stops, color scale, offset, histogram mode
  and orbit trap;
- interactive antialiasing render mode.

Interactive antialiasing always samples a regular subpixel grid. The
`antialiasing.samplingPattern` field is still written as `REGULAR` because
version 2 readers released before the deterministic-jitter pattern was removed
require it. On read the field is optional; the removed `DETERMINISTIC_JITTER`
value is accepted and renders with the regular grid, and any other value is
rejected.

Decimal viewport values are strings so parsing never passes through binary
floating point. Enum values use stable Java enum identifiers rather than
display labels. Colors use `#AARRGGBB` strings.

Version 1 remains readable. Its palette stops default to the selected preset,
histogram coloring and orbit traps default to off, and interactive
antialiasing uses the regular refined mode. Unsupported future versions and
invalid required values are rejected rather than partially applied.

## Atomic publication and recovery

Each generation is written and forced to a temporary file in the session
directory, then published with an atomic same-directory replacement. FractalLens
does not fall back to a non-atomic replacement on file systems that cannot
provide this guarantee. Before replacement, the current valid primary file is
published independently as `last-session.json.backup`.

An interrupted write therefore leaves either the complete old primary or the
complete new primary. Temporary files are not considered during startup. If
the primary is malformed or unreadable as a scene, the last valid backup is
used. Both JSON documents are bounded to 1 MiB before parsing.

## Scene history, bookmarks and shared presets (P2.2)

Edit → Undo and Redo restore complete `FractalScene` snapshots, including the
formula, exact decimal viewport, Julia parameters, iterations, coloring and
interactive antialiasing settings. The shortcuts are the platform shortcut key
with Z and Shift+Z. When a text field is focused, these commands continue to
operate on its draft instead of the scene. Undo/Redo availability follows focus
and draft history changes, including in separate dialog windows, without
reopening the Edit menu. History is local to the current window session and
retains the most recent 100 actions. A new scene
action after Undo discards Redo; selecting an unchanged value does not add an action.

Restoring a snapshot during a pending window resize uses the new render
dimensions while retaining the snapshot's exact viewport scale.

Drag and pinch changes are grouped at their gesture boundary; wheel events are
grouped by the existing render debounce. Window resize, display dimensions,
render scheduling and individual palette-animation frames do not add history
entries. Resize updates the current scene; saved history snapshots retain their
original mathematical scale and are rendered at the current window dimensions
when restored. Palette animation is stopped when a saved scene is restored.
The deep-zoom AA override remains a transient renderer setting, outside the
serialized scene contract.

The Bookmarks menu provides Add Bookmark, Reload Bookmarks, and a submenu for
each saved name with Restore and Delete. Names are trimmed, case-sensitive and
limited to 80 Unicode code points (characters, not UTF-8 bytes), without control
characters or malformed Unicode. Saving an existing name
replaces that bookmark. Each bookmark is a normal scene JSON file in a
`bookmarks` directory beside `last-session.json`; filenames contain the
lowercase hexadecimal UTF-8 encoding of the name, preventing path traversal
and case-insensitive filesystem collisions. Encoded names longer than 160
characters are split into directory components of at most 160 characters,
followed by the final `.json` filename. This bounds filename lengths even for
80 emoji. Existing single-file bookmarks retain their paths and remain readable,
replaceable and deletable without migration. Bookmark storage uses the same
atomic publication, 1 MiB read limit and previous-generation recovery as the
session store. Delete removes both primary and recovery files. Corruption with
no valid recovery copy is reported and preserved for recovery; Reload retries
after the files or directory permissions have been repaired. Storage runs on a
single background worker. Menu containers remain openable during storage work;
Add, Reload, Restore and Delete are disabled individually until it finishes.
Saved or reloaded entries become actionable immediately, including in the
native macOS menu, without reopening the menu or changing window focus.
Closing the window suppresses late UI callbacks and
allows already submitted writes to finish.

On macOS, Add Bookmark uses an owner-associated AppKit sheet with one native
name field, Save and Cancel. Return saves; Escape and Cancel discard the draft.
Invalid names keep the sheet open with a character-based inline error. Storage
starts asynchronously only after a valid Save. Closing the owner suppresses
pending Save callbacks. Bookmark and parameter sheets cannot overlap. The
JavaFX name dialog remains the fallback on other platforms or if the native
bridge cannot present the sheet.

View → Copy Scene Preset places the versioned scene JSON on the clipboard
(shortcut key + Shift+C). View → Apply Scene Preset from Clipboard
(shortcut key + Shift+V) validates the complete document before changing the
scene. Share the copied text, or copy the contents of a scene/bookmark JSON file
and apply it. No render dimensions or machine paths are included. Legacy
version 1 scenes remain accepted; unsupported versions, malformed values and
viewports beyond a formula's existing precision gate are rejected without
partially changing the scene or history. Precision validation uses the future
render target at the current window dimensions, including a pending resize.
Applying or restoring a preset is undoable.
Restoration cancels pending gestures, recoloring and superseded rendering,
updates the camera without decimal rounding, and synchronizes menus and title.

The application module opens its `app` package to `javafx.graphics` for
reflective JavaFX startup. The `app` and `ui` packages are not exported APIs;
scene-typed bookmark and view operations remain internal to the module.

Developer regression commands:

```shell
mvn -Dtest=SceneHistoryTest,BookmarkStoreTest,FractalSceneJsonTest test
mvn test
mvn -Dfractal.fx.tests=true "-Djavafx.cachedir=${TEMP_DIR}/fractallens-p22-fx" -DreuseForks=false -Dtest=MainMenuBarFxTest,FractalResizeFxTest,MacSystemMenuFxTest test
```

The JavaFX/native command requires an active desktop session. The separate
fullscreen resize checks also require `-Dfractal.fx.fullscreen=true`. Visible
acceptance should inspect scene Undo/Redo, bookmark naming (focus, invalid
name, Enter, Escape and Cancel), save/restore after restart, and copied preset
application in the running app. Model tests and skipped graphical tests do not
establish that acceptance.

Owner manual acceptance on 2026-10-14 covered bookmarks with nondefault scene
coordinates, Julia parameters, iterations and coloring; restart and Restore;
replacement and Delete (including absence after restart); and the original
name dialog's focus, validation, Return, Escape and Cancel. This acceptance
predates the native bookmark name sheet and its character-limit change. Separate
macOS developer checks of the replacement dialog are recorded in
[macOS UI](../platform/MACOS_UI.md#native-bookmark-name-sheet).

The owner subsequently confirmed both manual clipboard-preset stages without
errors: copying and applying a configured scene restored its settings, while
an unsupported schema showed an error without changing the current scene.
The owner explicitly accepted P2.2 as complete on 2026-10-14. This records
product workflow acceptance; it does not replace review, independent
verification where required, or delivery of the current bookmark fixes, and
does not extend the documented platform coverage.
