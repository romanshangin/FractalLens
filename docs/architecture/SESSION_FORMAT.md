# FractalLens session format

Normal launch restores the last closed scene: reproducible state only, excluding
window dimensions, render priority, pending work and transient animation.

## Storage locations

- macOS: `~/Library/Application Support/FractalLens/last-session.json`
- Windows: `%LOCALAPPDATA%\FractalLens\last-session.json`, falling back to
  `%APPDATA%` and then the user profile
- Linux: `$XDG_STATE_HOME/fractallens/last-session.json`, or
  `~/.local/state/fractallens/last-session.json`

Save on main-view close. Missing/malformed session tries the previous valid
generation, then default Mandelbrot; startup remains available.

Application data is separate from the earlier private app; old sessions are never
automatically moved/deleted. To reuse one, copy `last-session.json` to the new
platform location with both apps closed. Schema is unchanged.

## Schema contract

`schemaVersion` is required. Version 2 is the current write format and records:

- the fractal preset;
- viewport center and scale as decimal strings;
- base and per-zoom iteration settings;
- Julia constant real and imaginary components (defaulting to -0.8 and 0.156
  when absent from older version 2 sessions);
- palette identity, editable ARGB stops, color scale, offset, histogram mode
  and orbit trap;
- interactive antialiasing render modes for standard zoom (`renderMode`) and
  deep zoom (`deepZoomRenderMode`): `BASE_ONLY`, `FAST` or `REFINED`.

Interactive AA always uses a regular subpixel grid. Write `antialiasing.samplingPattern`
as `REGULAR` for pre-jitter-removal version 2 readers. Reading is optional:
`DETERMINISTIC_JITTER` also maps to regular; all other values are rejected.

Version 2 added `BASE_ONLY` without schema change; `FAST`/`REFINED` identifiers
and behavior persist. Earlier readers reject `BASE_ONLY` in `renderMode` rather
than partially applying the scene. Scenes using the earlier values remain
readable by those releases.

Version 2 also added optional `antialiasing.deepZoomRenderMode` without schema
change. Missing → `BASE_ONLY` (prior deep-AA-off default); unknown → reject.
Earlier readers ignore the field and keep deep AA off.

Decimal viewport values are strings so parsing never passes through binary
floating point. Enum values use stable Java enum identifiers rather than
display labels. Colors use `#AARRGGBB` strings.

Version 1 remains readable: preset palette stops, histogram/traps off, regular
refined AA. Reject future versions/invalid required values without partial application.

## Atomic publication and recovery

Write/force each generation to a session-directory temp, then atomically replace
in the same directory; no non-atomic fallback. First independently publish the
valid primary as `last-session.json.backup`.

Interrupted writes leave complete old/new primary; startup ignores temps. Invalid/
unreadable primary falls back to last valid backup. Bound both JSON documents to
1 MiB before parsing.

## Scene history, bookmarks and shared presets (P2.2)

Edit → Undo/Redo restores whole `FractalScene`: formula, decimal viewport, Julia,
iterations, coloring/AA. Shortcuts: platform modifier + Z/Shift+Z. Focused text
fields undo drafts instead; availability follows focus/draft history, including
separate dialogs, without reopening Edit. Window-local history retains 100 actions;
new action after Undo drops Redo, unchanged selections add nothing.

Restoring a snapshot during a pending window resize uses the new render
dimensions while retaining the snapshot's exact viewport scale.

Group drag/pinch at gesture boundaries, wheel at render debounce. Resize,
dimensions, scheduling/animation frames add no history. Resize updates current
scene; stored snapshots retain mathematical scale and restore at current dimensions.
Restore stops palette animation. Deep-AA override remains transient/outside serialization.

Bookmarks offers Add Bookmark, Reload Bookmarks and per-name Restore/Delete.
Trimmed case-sensitive names allow 80 Unicode code points, no controls/malformed
Unicode. Existing name replaces. Normal scene JSON lives in `bookmarks` beside
`last-session.json`; lowercase hex UTF-8 names prevent traversal/case-insensitive
collisions. Encodings >160 characters split into ≤160-character directory parts
and final `.json`, bounding even 80-emoji names. Existing single-file paths remain
readable/replaceable/deletable without migration. Use session atomic writes,
1 MiB reads and prior-generation recovery. Delete removes primary/recovery.
Unrecoverable corruption is reported/preserved; Reload retries after file/permission
repair. One background worker handles storage: menu containers stay openable,
Add/Reload/Restore/Delete disable individually until done. Saved/reloaded entries
activate immediately, including AppKit, without menu reopening/focus change.
Close suppresses late UI callbacks but lets submitted writes finish.

macOS Add Bookmark: owner AppKit sheet, native name field, Save/Cancel. Return
saves; Escape/Cancel discards. Invalid names keep inline character-based errors
and the sheet open. Only valid Save starts async storage; owner close suppresses
queued Save. Bookmark/parameter sheets cannot overlap. JavaFX fallback covers
other platforms or native presentation failure.

View → Copy Scene Preset (shortcut + Shift+C) copies versioned scene JSON;
Apply Scene Preset from Clipboard (shortcut + Shift+V) validates before mutation.
Share text or scene/bookmark JSON contents; no dimensions/machine paths. Version 1
is accepted; unsupported versions, malformed values or formula-precision violations
leave scene/history intact. Validate against future target at current dimensions,
including pending resize. Apply/restore is undoable, cancels gestures/recolor/stale
renders, restores decimal camera exactly and synchronizes menus/title.

The application module opens its `app` package to `javafx.graphics` for
reflective JavaFX startup. The `app` and `ui` packages are not exported APIs;
scene-typed bookmark and view operations remain internal to the module.

Developer regression commands:

```shell
mvn -Dtest=SceneHistoryTest,BookmarkStoreTest,FractalSceneJsonTest test
mvn test
mvn -Dfractal.fx.tests=true "-Djavafx.cachedir=${TEMP_DIR}/fractallens-p22-fx" -DreuseForks=false -Dtest=MainMenuBarFxTest,FractalResizeFxTest,MacSystemMenuFxTest test
```

The JavaFX/native command requires an active desktop session. The separate fullscreen
resize checks also require `-Dfractal.fx.fullscreen=true`. Visible acceptance should
inspect scene Undo/Redo, bookmark naming (focus, invalid name, Enter, Escape and
Cancel), save/restore after restart, and copied preset application in the running app.
Model tests and skipped graphical tests do not prove that acceptance.

Owner manual acceptance on 2026-10-14 covered bookmarks with nondefault scene
coordinates, Julia parameters, iterations and coloring; restart and Restore; replacement
and Delete (including absence after restart); and the original name dialog's focus,
validation, Return, Escape and Cancel. This acceptance predates the native bookmark name
sheet and its character-limit change. Separate macOS developer checks of the replacement
dialog are in [macOS UI](../platform/MACOS_UI.md#native-bookmark-name-sheet).

Owner confirmed both clipboard stages: configured scene copy/apply restores
settings; unsupported schema errors without mutation. P2.2 accepted 2026-10-14:
product workflow only, not replacement for review, required independent
verification/delivery of current bookmark fixes or expanded platform coverage.
