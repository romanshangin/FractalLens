# Julia deep zoom: latency and pixelation regression

## Report and causes

Original Julia iterated each pixel in BigDecimal. Small correctness passes did
not prove window-size usability.

Reported center ≈`0.000054529976490780839 - 0.000020399686433499972 i`, zoom
`1.6120713667355477e10`; exact input (>9,000 digits/value):
`src/test/resources/julia/reported-pixelation.txt`. First Julia step contracts
differences by `2 * abs(z0)`. Coordinate-only double gating missed rounding on
adding c: Standard display showed collapsed neighboring orbits/blocks. AA cannot
repair lost precision.

Navigation repeatedly added guard budgets, inflating precision to thousands of
digits and exact per-pixel orbit cost beyond pixel-scale needs.

## Implementation

- Julia uses a shared BigDecimal reference orbit, with the fixed c represented by the
  same binary values as JuliaFormula.
- Pixel offsets evolve with delta z' = 2 Z delta z + delta z squared, delta c = 0.
  Two-component double arithmetic carries the small displacement through the
  critical-point neighborhood. The error propagation uses the actual orbit
  derivative 2 abs(z), retaining rounding/error bounds through reference reindexing.
- Uncertain escape decisions and growing error use bounded per-worker
  recovery references, then an exact BigDecimal fallback.
- Pixel deltas below the normal-double range (P2.5) are carried as
  `delta * 2^exponent` with the error bound in the same units. The quadratic
  term carries the extra `2^exponent` factor; the delta is rescaled when its
  binary exponent drifts by 128 or more, and returns to plain doubles once the
  absolute delta exceeds `2^-900` or before reference reindexing. Every
  rescaling and subnormal rounding step adds an explicit `Double.MIN_VALUE`
  term to the error bound, so the existing gate still sends unreliable orbits
  to recovery and the exact fallback.
  Recovery state belongs to the sampler, not persistent worker ThreadLocals.
- The same sampler serves base frames, optional deep AA, and adaptive PNG export.
- Direct selection, the Deep Zoom indicator/presentation, and export share the
  Julia orbit-precision gate. Overview rendering stays on the standard backend.
- Derived viewports retain an existing guard budget rather than adding it again. Exact
  input coordinates remain authoritative; excessive render-grid precision is bounded by
  pixel depth plus guard digits. Ordinary existing grids and the canonical 9.1-v1
  manifest are unchanged.

## Evidence

These are diagnostic single runs, not a statistically paired performance gate.
AA was off. The image-producing measurement includes PNG encoding.

| Scene / CPU workers | Size | First ready region | Completion |
| --- | --- | --- | --- |
| Initial per-pixel BigDecimal, Spiral Detail at span 1e-16 / 4 | 96x64 | 102 ms | 534 ms |
| Initial reference prototype, same scene / 4 | 96x64 | 24 ms | 173 ms |
| Reported coordinates, two-component reference implementation / 4 | 2600x1675 | 97 ms | 53.61 s |
| Reported coordinates, reduced norm overhead / 4 | 640x412 | 68 ms | 4.75 s |

Inspected full-size output replaces blocks with fine spirals. First ready region
is a backend event, not loading-overlay removal; separate FX regression checks
completed visibility/loading removal.

Regressions compare sparse full-resolution reported-coordinate samples to doubled-
precision independent iteration, require one shared reference/no exact per-pixel
fallback, and test repelling fixed-point neighborhoods at multiple depths/all traps.
Also cover 1e-400, cancel/reuse/shifted grids/caps, precise AA/PNG and 500 zooms.

Run the diagnostic with:

```sh
mvn -q -Dtest=JuliaDeepZoomLatencyTest -Dfractal.julia.latency=true \
  -Dfractal.julia.reported=true -Dfractal.julia.width=2600 \
  -Dfractal.julia.height=1675 test
```

Particularly difficult orbits can still require the slower exact fallback. Deep
Julia is not guaranteed to have Mandelbrot BLA throughput.

## Subnormal deltas (P2.5)

### Profiling decision gate

Before P2.5, offsets below `Double.MIN_NORMAL` (window spans roughly <`1e-305`)
skipped perturbation: up to eight recovery references/worker, then BigDecimal per
remaining pixel. Measured before retain decision.

- Control: `3806a6d` (exact fallback for subnormal deltas).
- Candidate: the same base with the scaled-delta sampler and the parameterized
  `JuliaDeepZoomLatencyTest` driver (`fractal.julia.scale`,
  `fractal.julia.iterations`, `fractal.julia.workers`).
- Scene: Julia preset, center `-0.035 - 0.493 i`, 3000 iterations, no AA, no
  orbit trap, 4 CPU workers; one fresh JVM per run; control and candidate
  runs alternate.
- Environment: Apple M3 Pro (12 cores, 18 GiB), macOS 27.0.1 arm64, AC power,
  JDK 25 (Microsoft build), Maven 3.9.16 through the wrapper.
- Diagnostic latency runs, not a `run_baseline_pairs.py` campaign; the effect
  is more than an order of magnitude and well outside run-to-run spread.

| Span / size | Control first / complete (3 runs) | Candidate first / complete (3 runs) |
| --- | --- | --- |
| `1e-16` / 320x200 (normal deltas) | 22–23 ms / 437, 561, 467 ms | 21–23 ms / 408, 515, 458 ms |
| `1e-310` / 96x64 | 157–172 ms / 3.70, 4.01, 4.17 s | 39–43 ms / 216, 238, 232 ms |
| `1e-400` / 96x64 | 180–189 ms / 5.33, 5.32, 5.32 s | 42–48 ms / 218, 218, 266 ms |
| `1e-310` / 640x400 (1 control, 2 candidate) | 159 ms / 113.4 s | 60–62 ms / 1.53, 1.43 s |

Exact subnormal fallback is material: window frame nearly two minutes versus
scaled candidate about 1.5 s; normal offsets unchanged within noise. **Retain**.

### Correctness evidence

- PNG output of the control (exact fallback) and candidate is byte-identical
  for the `1e-310` and `1e-400` 96x64 frames above. Independent verification
  also found the 640x400 `1e-310` frame byte-identical; the control was built
  from the base commit with only the parameterized test driver added, and both
  images were written with `-Dfractal.julia.image=<path>`.
- Manual acceptance on the same macOS machine applied a scene preset centered
  on the repelling fixed point at span `1e-310` (3000 iterations). Deep Zoom
  presentation, rendering, PNG export, deep antialiasing, zoom and pan showed
  no defects, and the exported layout matched an independent coarse BigDecimal
  escape-time map of the same view.
- `JuliaDeepZoomRenderBackendTest` requires one shared reference and no exact
  fallback at spans `1e-306` (mixed normal/subnormal offsets), `1e-310` and
  `1e-400`, comparing sparse samples with a doubled-precision oracle. It also
  checks subnormal offsets around the repelling fixed point (computed to 500
  digits) with and without an orbit trap, where neighboring orbits must
  separate into different escape times. Both tests fail on the control.

Reproduce one run with:

```sh
./mvnw -q -Dtest=JuliaDeepZoomLatencyTest -Dfractal.julia.latency=true \
  -Dfractal.julia.scale=1e-310 -Dfractal.julia.width=640 \
  -Dfractal.julia.height=400 -Dfractal.julia.iterations=3000 \
  -Dfractal.julia.workers=4 test
```
