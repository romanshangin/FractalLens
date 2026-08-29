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
- [x] Publish completed rows immediately after pan reuse so deep-zoom pixels do not wait for an entire 32x32 tile before appearing.
- [x] Calculate only missing horizontal spans in partially reused tiles, avoiding synchronized per-pixel validity checks after pan.

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

Remaining items in this section are deferred while rendering-engine work is prioritized.

- [x] Replace the crowded top control row with a global toolbar, left inspector, canvas, and bottom render status area.
- [x] Add Reset View.
- [x] Display and edit center coordinates where appropriate.
- [x] Support native macOS trackpad pinch zoom, continuous two-finger panning, and directional swipe panning.
- Display zoom and iteration count.
- Add iteration controls and editable Julia parameters.
- Add keyboard navigation and render progress/status.
- Show user-facing errors instead of printing stack traces.
- Add copyable/shareable viewport presets.

## 5. Add history and reproducibility

- [x] Introduce an immutable `FractalScene` containing formula, viewport, iteration settings, and coloring, and pair every completed frame with the exact scene snapshot it represents.
- [x] Keep transient output dimensions and scheduling priority in a separate immutable `RenderTarget` so window resizes do not alter scene history.
- Add undo/redo and bookmarks based on `FractalScene` snapshots.
- Add JSON serialization and last-session restore.
- Add arbitrary-resolution export by combining a scene snapshot with an independent render target.

## 6. Expand the rendering engine

- [x] Add cubic Multibrot, Burning Ship, and Tricorn presets on reusable formula implementations.
- [x] Preserve smooth escape-time coloring as the default treatment for palette banding, with a slower seamless ping-pong cycle and perceptually uniform OKLab palette interpolation.
- [x] Precompute each OKLab gradient into a 65,536-entry lookup table so per-pixel coloring remains a constant-time array lookup.
- [x] Add basic PNG export of the current render without antialiasing.
- [x] Add adaptive 2x2–8x8 subpixel sampling to off-screen export, preserving the current output dimensions and averaging colors in linear light.
- [x] Add weak deterministic export dithering to reduce visible 8-bit gradient quantization without blurring fractal detail.
- [x] Add cancellable edge-adaptive 4x4 subpixel refinement after the interactive base frame is visible.
- [x] Add deterministic jitter as an optional, reproducible interactive sampling pattern.
- Investigate distance-estimation antialiasing after extending samples with derivative and distance data.
- Treat image-space edge filtering as an optional fast display mode rather than the source of export-quality output.
- Add histogram coloring as a separate two-pass tonal-mapping feature; it does not replace geometric antialiasing.
- Add orbit traps and editable palette stops.
- Add a render cache, resize reuse, and a separate high-resolution/off-screen export pipeline.

## 7. Implement deep zoom

- [x] Add a conservative Julia-specific double-precision guard so unstable orbit blocks are not presented as real detail before a deep-zoom backend exists.
- Introduce an arbitrary-precision complex coordinate type with precision derived from the current scale.
- Store the viewport center and scale without `double` precision loss, and update zoom, pan, resize, display, serialization, and preset handling accordingly.
- Keep the existing direct `double` renderer for normal zoom levels and select a separate deep-zoom backend only when hardware precision becomes insufficient.
- Build a high-precision Mandelbrot reference orbit for the current viewport while retaining hardware floating point for per-pixel deltas.
- Add a specialized perturbation Mandelbrot calculator that shares the reference orbit across tiles and still produces the final orbit values required by smooth coloring.
- Detect unreliable perturbation results and recover through rebasing, additional reference orbits, or direct high-precision fallback for affected pixels.
- Cache reference orbits across compatible renders and define cancellation and memory limits for orbit construction.
- Verify deep-zoom output against direct arbitrary-precision reference renders, including boundary points, long-running interior points, glitches, pan/zoom transitions, and cancellation.
- Benchmark the backend-selection threshold, reference-orbit overhead, cache effectiveness, time to first visible tile, and total render time.
- Initially enable deep zoom only for Mandelbrot; retain the current Julia path until its coordinate and orbit semantics receive a separate design.
- Support multiple numeric backends after the first implementation is correct and measured, then investigate series approximation as a later optimization.

This phase should remain separate from the current pan-reuse optimization.

## Target milestone

Deliver a stable interactive Mandelbrot/Julia explorer with smooth pan/zoom, measured frame reuse, PNG export, documentation, CI, and a packaged runtime.
