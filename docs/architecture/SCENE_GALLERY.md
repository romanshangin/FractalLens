# Interesting Places gallery

Go to → Interesting Places… (platform shortcut + Shift+G) opens a curated catalog of
named scenes, grouped by fractal, each with a preview and a short description.
User-visible behavior is described in
[macOS interface](../platform/MACOS_UI.md#interesting-places); this document owns
the catalog contract.

## Catalog

The catalog is bundled read-only application data:
`src/main/resources/com/shangin/fractal/scene/gallery-catalog.json`, loaded by
`scene/SceneGallery`. It is independent of user data: the gallery never reads or
writes bookmarks, sessions or remembered views, so a catalog update can neither
change nor remove a bookmark. A user who wants to keep a place saves it with Add
Bookmark, which stores an ordinary scene file.

```json
{
  "catalogVersion": 1,
  "entries": [
    {
      "id": "julia-douady-rabbit",
      "name": "Douady Rabbit",
      "description": "…",
      "fractal": "JULIA",
      "viewport": {"centerReal": "0", "centerImaginary": "0", "scale": "2.4"},
      "juliaParameters": {"real": -0.12256116687665, "imaginary": 0.74486176661974},
      "iterations": {"baseIterations": 300, "iterationsPerZoomLevel": 50},
      "coloring": {"palette": "CORAL", "colorScale": 1.0, "offset": 0.0,
                   "histogramColoring": false, "orbitTrap": "NONE"}
    }
  ]
}
```

- `catalogVersion` is required; version 1 is the only supported version. Another
  version, malformed JSON, a document over 1 MiB, a missing or non-array `entries`
  list or more than 1,000 entries rejects the whole catalog, and the window shows
  an error instead of places.
- `id`: 1–64 lowercase letters, digits and single hyphens, unique in the catalog.
  `name` (up to 80 code points) and `description` (up to 400) are trimmed, must not
  be empty and must not contain control characters.
- `fractal` is a `FractalPreset` name. `viewport` uses decimal strings, exactly as
  scene files do, so every published digit is kept; JSON numbers are rejected there.
- `juliaParameters` is required for `JULIA` and rejected for every other fractal.
  Its components are binary64, as in scene files.
- `iterations` is optional (defaults 300 and 50). In `coloring`, `palette` is
  required and selects the preset stops; `colorScale`, `offset`,
  `histogramColoring` and `orbitTrap` are optional with scene defaults.
- Unknown fields are rejected, so a misspelled field cannot silently fall back to a
  default.

An entry that fails validation is skipped, logged and reported in the window
("Some places could not be loaded"); the remaining entries stay available. The
bundled catalog must load with no skipped entries (`SceneGalleryTest`).

## Opening a place

Opening a place applies its scene through `FractalView.applyScene`, the same path
as restoring a bookmark or applying a shared preset: precision is validated against
the current window before mutation, the change is one history step, the current
render is cancelled, the loading screen covers a fractal change and no stale pixels
are published. The entry fixes fractal, viewport, Julia constant, iterations and
coloring; the viewer's standard and deep zoom antialiasing modes are kept, because
they are a presentation choice rather than part of a place. If a place cannot be
rendered at the current size, an error is shown, the window stays open and the
scene and history are unchanged. Two checks run before any mutation: the camera's
coordinate precision gate and the render backends' own support for the complete
scene (formula, coloring and viewport at the current render grid). The second
rejects, for example, deep Mandelbrot with an orbit trap, which the camera accepts
but the perturbation backend does not render.

## Presentation

`SceneGalleryWindow` owns the catalog, preview rendering and cache, sessions and
opening a place. It shows them through a `Presentation`: the native AppKit panel
`MacGalleryPanel` (`src/main/macos/FractalGalleryPanel.m`) when the bridge is
available, otherwise `JavaFxGalleryPresentation`. If the native panel cannot be
created, the JavaFX window is used and a warning is logged. The native panel
reports a chosen place id or its closing; Java decides whether opening succeeded
and closes the panel only then. Closing a presentation from Java sends no callback,
and a callback from an earlier session is ignored.

## Previews

Previews are rendered when the window first needs them, by
`render/ScenePreviewRenderer`: a base-quality (not antialiased) frame from the same
CPU backends as interactive rendering (direct double, Mandelbrot perturbation or
Julia deep zoom, chosen by precision), the scene's iteration policy and its
coloring, including histogram coloring. They are always consistent with the
current renderer; no image files are bundled. Each preview is 240 × 150 logical
pixels at the gallery window's own output scale (1× to 2×): the native panel's
backing scale, or the JavaFX window's output scale read after it is shown. One background thread loads the catalog and renders previews in catalog
order; completed previews are kept for the rest of the run by entry and raster
size, so reopening the window on a display with another scale renders new ones.
A window moved to another display while open keeps its current previews. Hiding the window cancels pending previews; closing the main
window also closes the renderer after queued work stops.

## Entries and sources

Mandelbrot, Multibrot, Burning Ship and Tricorn views reuse the coordinates and
framing documented in [Go to destinations](FRACTAL_DESTINATIONS.md), with palettes
chosen for the gallery. Additional entries:

- **Spiral Zoom Target** (Mandelbrot): the center of the Wikimedia Commons
  animation *Mandelbrot sequence new.gif*,
  (-0.743643887037158704752191506114774, 0.131825904205311970493132056385139),
  kept with all published digits, at span `1e-10` and iterations 1000 + 100 per
  zoom doubling.
- **Branch Point at c = i** (Mandelbrot, deep zoom): c = i is preperiodic
  (0 → i → −1 + i → −i → −1 + i …), so the set is asymptotically self-similar there.
  Span `2.4e-24` (10^24 times the home view) needs the perturbation backend.
- **Dragon Spiral** (Julia, c = −0.8 + 0.156i): from the Julia destinations.
- **Douady Rabbit** (Julia): c is the root of c³ + 2c² + c + 1 = 0 in the upper
  period-3 component, −0.122561166876654 + 0.744861766619744i, stored to 14 digits.
- **San Marco Dragon** (Julia, c = −0.75) and **Dendrite** (Julia, c = i): standard
  parameters with locally chosen framing.
- **Dendrite Branch Point** (Julia, c = i, deep zoom): z = i is preperiodic for this
  c (i → −1 + i → −i → −1 + i …) and lies on the Julia set. Span `2.4e-20` needs
  the Julia deep-zoom backend.
- **Junction Valley** (cubic Multibrot): the period-2 bulb is attached where the
  fixed-point multiplier is −1, at c = 4i / (3√3) ≈ 0.7698i; the view is locally
  framed to its right. It replaces the destination "Upper Antenna", whose framing
  shows mostly interior in a gallery preview.

## Validation

```shell
./mvnw -Dtest=SceneGalleryTest,ScenePreviewRendererTest,GalleryRenderModesTest test
./mvnw -Dfractal.fx.tests=true "-Djavafx.cachedir=${TEMP_DIR}/fractallens-fx-gallery" -DreuseForks=false -Dtest=SceneGalleryWindowFxTest,SceneGalleryWindowStatesFxTest,SceneGalleryExportFxTest,MacGalleryPanelFxTest,FractalDestinationsFxTest test
```

`SceneGalleryTest` covers the bundled catalog, grouping, exact coordinates,
round trips through the scene file format, defaults, invalid entries and rejected
catalogs. `ScenePreviewRendererTest` renders every bundled entry with the current
renderer and requires visible structure, checks that the gallery includes standard,
Mandelbrot deep zoom and Julia deep zoom places, and that a cancelled preview
publishes nothing. `GalleryRenderModesTest` runs interactive antialiasing on
every place (the deep candidate-only path for both deep zoom places) and exports
every place as an adaptively antialiased PNG. `SceneGalleryWindowFxTest` opens the window, waits for all
previews, opens places (standard and deep zoom), checks history, the viewer's
antialiasing modes, that no bookmark data is created, cached previews on reopening,
and closing with the main window. `SceneGalleryWindowStatesFxTest` substitutes the catalog
and the preview renderer to cover the catalog loading indicator, the load error,
the skipped-entries notice, preview indicators replaced by images or by "Preview
unavailable", cancellation when the window is hidden and new requests when it is
reopened, a late preview after closing (dropped, with the renderer closed only
after it returns), preview rasters that follow the gallery window's scale
instead of reusing cached previews of another size, and places beyond precision
or beyond backend support that leave scene and history unchanged.
`SceneGalleryExportFxTest` opens a standard and a deep zoom place in the full main
view through the platform presentation (the native panel on macOS) and exports each
through the application's PNG export path. `MacGalleryPanelFxTest` (macOS) shows the
production native panel and drives it with real key events: every place and
preview at the panel's backing scale, the initial selection, the right arrow and
Return opening the selected place with Undo restoring the previous scene, Escape
closing the panel, cached previews on reopening, closing from Java without
callbacks, the catalog load error, the skipped-entries notice, a preview the
renderer cannot draw shown as "Preview unavailable", and a rejected place that
leaves the panel open and the scene unchanged. `SceneGalleryWindowStatesFxTest`
also checks that a native panel that cannot be created falls back to the JavaFX
window. `SceneGalleryWindowFxTest` and `SceneGalleryWindowStatesFxTest` cover the
JavaFX window.
