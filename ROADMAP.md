# FractalUI development roadmap

## 1. Restore a stable green build

- [x] Fix compilation and run the complete test suite.
- [x] Remove duplication from the parallel rendering pipeline.
- [x] Add coverage for render timing statistics.
- [x] Verify cancellation during partially completed pan rendering.
- [x] Cover frame-reuse fast paths in every direction, including zero shift and no overlap.
- [x] Isolate temporary profiling from the normal rendering pipeline.
- [ ] Split the current optimization work into focused commits.

Exit criterion: `mvn test` succeeds and temporary profiling code is isolated from the main rendering pipeline.

## 2. Prove the effectiveness of frame reuse

- Measure time to first correct updated region and total pan-render time.
- Track reused-pixel percentage, calculation/colorization time, tile count, and p50/p95 timings.
- Benchmark 1080p and HiDPI scenarios with short, medium, and near-full-frame pans.
- Move benchmarks into a dedicated Maven profile and compare tile sizes such as 16, 32, and 64.

## 3. Strengthen user interaction

- Add Reset View.
- Display and edit center coordinates where appropriate.
- Display zoom and iteration count.
- Add iteration controls and editable Julia parameters.
- Add keyboard navigation and render progress/status.
- Show user-facing errors instead of printing stack traces.
- Add PNG export and copyable/shareable viewport presets.

## 4. Add history and reproducibility

- Introduce an immutable `FractalScene` containing formula, parameters, viewport, iteration settings, coloring, and render dimensions.
- Add undo/redo, bookmarks, JSON serialization, last-session restore, and arbitrary-resolution export.

## 5. Expand the rendering engine

- Add Multibrot and Burning Ship.
- Add histogram coloring, distance estimation, supersampling, orbit traps, and editable palette stops.
- Add a render cache, resize reuse, and a separate high-resolution/off-screen export pipeline.

## 6. Implement deep zoom

- Add arbitrary-precision coordinates.
- Investigate perturbation/reference-orbit rendering and series approximation.
- Cache reference orbits and support multiple numeric backends.

This phase should remain separate from the current pan-reuse optimization.

## Target milestone

Deliver a stable interactive Mandelbrot/Julia explorer with smooth pan/zoom, measured frame reuse, PNG export, documentation, CI, and a packaged runtime.
