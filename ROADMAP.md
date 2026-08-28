# FractalUI development roadmap

## 1. Restore a stable green build

- [x] Fix compilation and run the complete test suite.
- [x] Remove duplication from the parallel rendering pipeline.
- [x] Add coverage for render timing statistics.
- [x] Verify cancellation during partially completed pan rendering.
- [x] Cover frame-reuse fast paths in every direction, including zero shift and no overlap.
- [x] Isolate temporary profiling from the normal rendering pipeline.
- [x] Split the current optimization work into focused commits.

Exit criterion: `mvn test` succeeds and temporary profiling code is isolated from the main rendering pipeline.

## 2. Prove the effectiveness of frame reuse

- [x] Measure time to first correct updated region and total pan-render time.
- [x] Track reused-pixel percentage, calculation/colorization time, tile count, and p50/p95 timings.
- [x] Benchmark 1080p and HiDPI scenarios with short, medium, and near-full-frame pans.
- [x] Move benchmarks into a dedicated Maven profile and compare tile sizes 16, 32, and 64.

## 3. Optimize Mandelbrot and Julia calculation

- [x] Establish dedicated Mandelbrot and Julia calculation benchmarks covering throughput, nanoseconds per pixel, iteration distributions, 1080p/HiDPI, and multiple zoom levels.
- [ ] Add safe algorithmic fast paths: Mandelbrot cardioid and period-2 bulb rejection, periodicity checking, and other proven interior-point shortcuts.
- [ ] Investigate Mandelbrot symmetry around the real axis.
- [ ] Compare the scalar kernel with the Java Vector API.
- [ ] Retune tile size and worker count after optimizing the calculation kernel.
- [ ] Evaluate whether Julia benefits from a specialized calculation pipeline.
- [ ] After exhausting double-precision optimizations, investigate perturbation and reference-orbit rendering for deep zoom.

## 4. Strengthen user interaction

- Add Reset View.
- Display and edit center coordinates where appropriate.
- Display zoom and iteration count.
- Add iteration controls and editable Julia parameters.
- Add keyboard navigation and render progress/status.
- Show user-facing errors instead of printing stack traces.
- Add PNG export and copyable/shareable viewport presets.

## 5. Add history and reproducibility

- Introduce an immutable `FractalScene` containing formula, parameters, viewport, iteration settings, coloring, and render dimensions.
- Add undo/redo, bookmarks, JSON serialization, last-session restore, and arbitrary-resolution export.

## 6. Expand the rendering engine

- Add Multibrot and Burning Ship.
- Add histogram coloring, distance estimation, supersampling, orbit traps, and editable palette stops.
- Add a render cache, resize reuse, and a separate high-resolution/off-screen export pipeline.

## 7. Implement deep zoom

- Add arbitrary-precision coordinates.
- Investigate perturbation/reference-orbit rendering and series approximation.
- Cache reference orbits and support multiple numeric backends.

This phase should remain separate from the current pan-reuse optimization.

## Target milestone

Deliver a stable interactive Mandelbrot/Julia explorer with smooth pan/zoom, measured frame reuse, PNG export, documentation, CI, and a packaged runtime.
