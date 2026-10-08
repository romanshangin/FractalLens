# Julia deep zoom: latency and pixelation regression

## Report and causes

The initial Julia backend iterated every pixel with BigDecimal. Small correctness
fixtures passed, but this did not establish usable window-size latency.

The supplied pixelation case has center approximately
`0.000054529976490780839 - 0.000020399686433499972 i` and zoom
`1.6120713667355477e10`. The exact copied input is retained in
`src/test/resources/julia/reported-pixelation.txt` (over 9,000 digits per value).
At this center, the first Julia step contracts pixel differences by roughly
`2 * abs(z0)`. The coordinate-only double gate missed rounding when adding c,
so the application displayed Standard while neighboring orbits collapsed into
blocks. More AA samples could not repair that loss.

Navigation also added another guard-digit budget to previously computed values.
Repeated gestures grew their precision into thousands of digits. Per-pixel exact
orbits consequently became much more expensive than needed for the pixel scale.

## Implementation

- Julia uses a shared BigDecimal reference orbit, with the fixed c represented
  by the same exact binary values as JuliaFormula.
- Pixel offsets evolve with delta z' = 2 Z delta z + delta z squared, delta c = 0.
  Two-component double arithmetic carries the small displacement through the
  critical-point neighborhood. The error propagation uses the actual orbit
  derivative 2 abs(z), retaining rounding/error bounds through reference reindexing.
- Uncertain escape decisions, growing error, and unrepresentable deltas use
  bounded per-worker recovery references, then an exact BigDecimal fallback.
  Recovery state belongs to the sampler, not persistent worker ThreadLocals.
- The same sampler serves base frames, optional deep AA, and adaptive PNG export.
- Direct selection, the Deep Zoom indicator/presentation, and export share the
  Julia orbit-precision gate. Overview rendering stays on the standard backend.
- Derived viewports retain an existing guard budget rather than adding it again.
  Exact input coordinates remain authoritative; excessive render-grid precision
  is bounded by pixel depth plus guard digits. Ordinary existing grids and the
  canonical 9.1-v1 manifest remain unchanged.

## Evidence

These are diagnostic single runs, not a statistically paired performance gate.
AA was off. The image-producing measurement includes PNG encoding.

| Scene / CPU workers | Size | First ready region | Completion |
| --- | --- | --- | --- |
| Initial per-pixel BigDecimal, Spiral Detail at span 1e-16 / 4 | 96x64 | 102 ms | 534 ms |
| Initial reference prototype, same scene / 4 | 96x64 | 24 ms | 173 ms |
| Reported coordinates, two-component reference implementation / 4 | 2600x1675 | 97 ms | 53.61 s |
| Reported coordinates, reduced norm overhead / 4 | 640x412 | 68 ms | 4.75 s |

The full-size output was visually inspected: the block artifacts are replaced
by fine spiral detail. First ready region is a backend event, not a claim that
an initial loading overlay has already been removed. The JavaFX regression
separately checks completed-frame visibility and loading-layer removal.

Regression coverage compares sparse full-resolution samples at the exact reported
coordinates with independent doubled-precision iteration, requires a single
shared reference and no per-pixel exact fallback there, and checks repelling
fixed-point neighborhoods at multiple depths with every orbit trap. Other checks
cover scales through 1e-400, cancellation, ready-pixel reuse, shifted grids,
iteration caps, precise AA/PNG sampling, and 500 consecutive zoom gestures.

Run the diagnostic with:

```sh
mvn -q -Dtest=JuliaDeepZoomLatencyTest -Dfractal.julia.latency=true \
  -Dfractal.julia.reported=true -Dfractal.julia.width=2600 \
  -Dfractal.julia.height=1675 test
```

Subnormal deltas and particularly difficult orbits can still require the slower
exact fallback. Deep Julia is not guaranteed to have Mandelbrot BLA throughput.
