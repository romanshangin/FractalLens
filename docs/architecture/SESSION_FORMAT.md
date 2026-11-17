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

Application preferences that are not scene state live beside it in
`preferences.properties` (Java properties, UTF-8): currently only
`widePreview=on|off`, written atomically when the user changes it in Settings.
An absent, malformed or unknown value keeps the application default; see
[Wide navigation preview](../platform/MACOS_UI.md#wide-navigation-preview).

The last view of each fractal lives beside it in `fractal-views.properties`
(Java properties, UTF-8), written atomically on main-view close. Each fractal has
`<PRESET>.centerReal`, `<PRESET>.centerImaginary` and `<PRESET>.scale` decimal
strings with every digit kept; `<PRESET>` is the `fractal` enum name. The file is
not part of any scene, so bookmarks, shared presets, history and export are
unaffected. At launch the active fractal keeps the restored session's view and the
others are remembered for the Fractal menu. A missing file, a file over 1 MiB, a
malformed file, or an entry with a missing, non-decimal or non-positive value only
makes the affected fractals open at their home views.

Completed base frames are stored separately, in the platform cache directory
(P2.7 Stage B, first increment). See
[Stored completed frames](#stored-completed-frames-p27-stage-b).

Application data is separate from the earlier private app; old sessions are never
automatically moved/deleted. To reuse one, copy `last-session.json` to the new
platform location with both apps closed. Schema is unchanged.

## Schema contract

`schemaVersion` is required. Version 2 is the current write format and records:

- the fractal preset;
- viewport center and scale as decimal strings;
- base and per-zoom iteration settings;
- Julia constant real and imaginary components (defaulting to -0.8 and 0.156
  when absent from older version 2 sessions); they are binary64, and
  both Open Julia Set Here actions round the selected decimal Mandelbrot point
  to them once (the same-zoom action centers its view on the rounded constant);
- palette identity, editable ARGB stops, color scale, offset, histogram mode
  and orbit trap;
- interactive antialiasing render modes for standard zoom (`renderMode`) and
  deep zoom (`deepZoomRenderMode`): `BASE_ONLY`, `FAST` or `REFINED`.

Viewport strings are stored and restored without rounding. On restore the working
precision is the larger of the depth budget (integer digits, scale depth and guard
digits) and the stored digit count; guard digits are not added again to values that
already carry them, so repeated save/restore and Go to cycles do not grow it.
Precision reached at depth is kept after zooming out. Older scenes that already
stored inflated digit counts load unchanged and are not shortened automatically.

Copy Coordinates and Zoom and Go to Coordinates show compact text instead: the
center rounded to within 0.0005 of the source render-grid pixel step, and the zoom
(relative to the fitted initial view, so dependent on window aspect) to 12
significant digits. That text is never stored. Go to replaces only fields whose
text was edited; untouched fields keep the live exact values, so opening and
confirming without edits changes neither the scene nor the undo history. Scenes,
bookmarks, shared presets and undo always keep the exact viewport.

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

## Stored completed frames (P2.7 Stage B)

`PersistentFrameStore` keeps the numerical base samples of completed frames, so
a view rendered in an earlier launch (the restored session or a remembered
fractal view) is shown without recalculating its samples. The data is
recalculable, so it lives in the platform cache directory rather than in the
backed-up application data:

- macOS: `~/Library/Caches/FractalLens/frame-cache/`
- Windows: `%LOCALAPPDATA%\FractalLens\frame-cache\`, falling back to the user
  profile (Windows has no separate cache root)
- Linux: `$XDG_CACHE_HOME/fractallens/frame-cache/`, or
  `~/.cache/fractallens/frame-cache/`

It is independent of scene JSON: no scene, bookmark, preset or history format
changes, and deleting the directory only costs recalculation.
`-Dfractal.frameStore.enabled=false` disables it. Only the application's main
view uses it. A main view opened on a non-default session store (as JavaFX
tests do with temporary directories) keeps its frames in `frame-cache/` beside
that session file; Surefire also points `XDG_CACHE_HOME` and `LOCALAPPDATA` into
`target/`, so tests never reach the real cache.

- **Contents.** One `<sha-256 of key>.frame` file per frame: iterations, smooth
  iterations, escape flags and, when the formula stores them, orbit-trap
  distances, uncompressed and big-endian. Palettes, coloring and display mode
  are not stored; colors are derived from the samples, so palette changes reuse
  a restored frame.
- **Antialiasing.** Finished AA is stored beside its base frame as
  `<sha-256 of key>.aa`, in the form kept in memory beside a cached frame: the AA
  display colors with their validity and the coloring that produced them
  (palette identity and stops, color scale, offset, histogram mode and orbit trap),
  and the palette-independent AA samples (16-bit phases, escape masks, edge
  strengths, deep-zoom flags, color scale, tested threshold and budget-rejection
  mark). The colors are shown only for an equal coloring; the samples serve any
  smooth palette of the same color scale. Another palette recolors without new
  supersampling when the stored samples cover all of its AA candidates.
  Otherwise (a steeper palette than every finished pass tested, or samples the
  cache dropped for its budget, the stored rejection mark) a pass over the
  whole frame runs: it tests again every pixel without matching cached samples,
  including pixels tested earlier that were not candidates, and supersamples
  only the new palette's candidates whose samples are not cached. Histogram and
  orbit-trap colorings have no samples, so their AA restores only for the same
  coloring. A finished pass that found no candidate keeps an empty sample set
  with its color scale and tested threshold, so such a frame (for example deep
  Julia at `1e-16`) recolors after a restart or a fractal switch without
  testing its pixels again; the header's samples flag tells an empty set from
  AA stored without samples. A file is written only beside a stored base frame, after the visit's
  AA pass, with the same 1 s settle delay as frames; AA whose complete header,
  payload checksum included, equals the stored file's is not rewritten, so a
  restored visit that keeps its AA unchanged writes nothing. AA retained with
  colors but without samples (the in-memory frame cache drops AA samples first
  when it runs short, for example while another fractal is shown) keeps the
  samples already stored for the frame, which are exact for its key and
  `ANTIALIAS_VERSION`; returning to the frame therefore neither rewrites
  unchanged AA nor loses its samples. Such a return also reads the stored
  samples on the render thread once the reused frame completes (only on the
  viewport's fresh grid, where they can match) and binds them to the
  interactive AA service, so a palette change in the same launch recolors
  without a new AA pass. A return in Base only binds them hidden, so a later
  switch to an AA mode reuses them instead of supersampling again. The header
  repeats the frame key and
  grid and carries the AA-specific counts and whether samples are stored, bounded before allocation; samples
  whose memory would exceed the interactive AA sample cache budget
  (`fractal.aaCache.maxBytes`, 512 MiB by default) are rejected, and the whole
  payload's length and CRC-32 are checked in a streaming pass before any array
  sized by the header is allocated. Pixel indices must be in range and distinct,
  phase counts 1–32 and masks and phases consistent before any of it is used. It is read on
  the render thread right after its base frame, so an exact hit runs neither
  candidate detection nor supersampling. In Base only mode the restored AA
  colors stay hidden with the frame, so switching to an AA mode shows them
  without another pass. Deleting or rejecting a base frame deletes its AA; AA
  without a stored frame is never read.
- **Key.** Formula and parameters (doubles by bit pattern), orbit trap, sample
  accuracy, the decimal center and scale exactly as held by the viewport, the
  render grid derived from them (double and decimal origin, steps, offsets,
  conjugate-symmetry height, math context), render width/height and iteration
  limit. The viewport's working precision enters only through that grid, so a
  view restored from scene JSON hits the entry saved from the navigated view.
  The header repeats the full key and grid, which must match. Custom
  (non-preset) formulas are not stored. The display scale is not part of the
  key: a restored view keeps its exact viewport, and the render size is the
  window's logical size times the scale, rounded up to even pixels. With the
  other key fields unchanged, a scale change misses only when it changes that
  rounded render size; one that keeps it (such as a window half as wide at
  twice the scale, or a slightly different scale that rounds to the same size)
  yields the same grid and restores the stored frame and AA exactly.
- **Pan limitation.** Only frames on their viewport's fresh grid, and their AA,
  are stored. A
  frame completed by pan reuse keeps the shifted grid of its source frame,
  whose sample positions differ from the fresh grid, so it is not stored; a view
  left after panning is restored only if it was also rendered fresh (for
  example after a zoom). Within one launch the
  memory cache still reuses it.
- **Versions.** `FORMAT_VERSION` covers the frame file layout and
  `REFINEMENT_FORMAT_VERSION` the AA file layout. `CALCULATION_VERSION` covers
  calculated sample semantics and must be incremented whenever a formula,
  backend selection, precision policy or sample encoding change alters the
  samples of an unchanged key; older frames and their AA are then discarded.
  `ANTIALIAS_VERSION` covers AA semantics (subpixel sampling grids, candidate
  edge thresholds, phase encoding and color averaging) and must be incremented
  whenever such a change alters the AA stored for an unchanged frame and
  coloring; older AA is then discarded while its frames stay valid.
  Both became 2 when deep recovery references became coordinate-determined
  (2026-11-15): version 1 holds deep Julia frames and deep AA computed with
  order-dependent recovery references, which a recalculation need not
  reproduce, so those entries are discarded and recalculated.
  Both became 3 on 2026-11-16: deep Mandelbrot base frames followed with
  coordinate-determined recovery references (version 2 frames used references
  built in glitch-discovery order), and scalar perturbation, which recovery
  uses for base frames and deep Mandelbrot AA, now sends escape decisions
  within its rounding bound to exact iteration; deep AA samples whose offset
  loses pixel resolution as a double use scaled deltas, whose loop, also used
  by base frames beyond double range, now converts exactly and checks escape
  decisions the same way. Version 2 entries are
  discarded and recalculated.
  Both became 4 on 2026-11-17: deep Mandelbrot escape decisions near the radius
  changed (exact reference escape margins, reference orbits that continue past
  their escape, BLA blocks that skip no escape test near the radius, decisions
  from the exact reference margin, and recovery results only within their error
  bound), so some version 3 pixels differ from a recalculation. Version 3
  entries are discarded and recalculated.
  `REFINEMENT_FORMAT_VERSION` became 2 on 2026-11-16, when the AA header gained
  its samples flag; version 1 AA files are discarded and their AA recalculated
  on the next visit, while their frames stay valid.
- **Loading.** Only an empty frame (no reused pixels) is restored, on the render
  thread inside the normal render call, so generations, cancellation and atomic
  publication are unchanged: the frame is published as one completed region.
  Header lengths are bounded before reading, the file length must equal the
  expected payload, escape flags must be 0 or 1 and the payload CRC-32 must
  match before any pixel is marked ready. Cancellation stops between 1 MiB
  chunks and leaves every pixel not ready. Mismatched, truncated, corrupt or
  outdated entries are deleted and the frame is calculated normally; read
  failures also fall back to calculation.
- **Saving.** A frame the wrapped backend completes is written by a background
  writer once it has stayed the most recently completed frame for 1 s, so a
  gesture that completes many frames writes only where it stops. An existing
  entry for the same key is not rewritten. Files are written to a temporary
  name and published by an atomic move; no fsync is issued, so an entry lost to
  a crash is rejected by its length or checksum. Write failures are logged once
  and rendering continues. Closing writes the frame and then the AA still
  waiting for their delay and waits at most 3 s; temporary files older than 10 minutes are
  removed by a later store.
- **Budget.** All frames and AA files share a 1 GiB disk budget; after each
  write the least recently written or restored entries beyond it are deleted.
  A base frame and its AA are one entry, as recent as its newer file; an entry
  that does not fit keeps its frame without the AA when the frame alone fits,
  and AA without its frame is deleted. An AA write keeps its own frame, AA that
  does not fit the budget together with its frame is not written, and a frame
  larger than the whole budget is not stored. At 3024x1964 a frame without
  orbit-trap distances takes 73.6 MiB (118.9 MiB with them); AA colors add
  about 28 MiB, and AA samples 14 bytes per candidate pixel plus 2 bytes per
  subpixel sample (38 MiB of AA in all for the Refined home view), so the
  budget keeps about nine such views with AA. Files are not compressed:
  Deflate halves typical frames, but inflating them takes 8–10 times the
  uncompressed read (see the
  [Stage B validation](../../benchmarks/STAGE_B_PERSISTENCE_VALIDATION.md)).
