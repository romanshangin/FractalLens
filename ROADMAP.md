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
- [x] Add Mandelbrot main-cardioid and period-2-bulb interior rejection.
- [x] Evaluate exact periodicity checking; reject it because benchmark overhead exceeds the benefit for current scenarios.
- [x] Evaluate other proven interior-point shortcuts; retain only the exact main-cardioid and period-2-bulb tests because higher-period components lack equally cheap exact membership predicates and approximate bulb circles risk false interiors.
- [x] Evaluate cached orbit squares; reject the manual optimization because the JIT already eliminates the repeated work and Julia regresses.
- [x] Measure `FractalSample` allocation cost; retain the value-returning API because escape analysis removes most allocations and the remaining cost is negligible in iteration-heavy scenes.
- [x] Reuse conjugate Mandelbrot rows for empty frames centered around the real axis; verify exact output equivalence and a 1.72-1.88x parallel-render speedup.
- [x] Compare the scalar kernel with the Java Vector API; retain scalar production code because the current 128-bit/two-double species regresses overview rendering and provides only a small deep-zoom gain.
- [x] Retune tile size and worker count after optimizing the calculation kernel; retain 32-pixel tiles and `CPU - 1` workers as the best throughput, progressive-latency, and UI-responsiveness balance.
- [x] Evaluate a specialized direct-to-data Julia pipeline; retain the generic formula pipeline because HotSpot removes its abstraction cost and the measured difference is about 1%.
- [x] Investigate perturbation/reference-orbit rendering; defer production integration until arbitrary-precision coordinates provide a high-precision reference orbit.

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
- Implement perturbation/reference-orbit rendering with glitch detection and rebasing, then investigate series approximation.
- Cache reference orbits and support multiple numeric backends.

This phase should remain separate from the current pan-reuse optimization.

## Target milestone

Deliver a stable interactive Mandelbrot/Julia explorer with smooth pan/zoom, measured frame reuse, PNG export, documentation, CI, and a packaged runtime.
