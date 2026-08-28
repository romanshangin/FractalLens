# Frame reuse benchmark results

Measured on 2026-08-27 with Java 26.0.1 on arm64, using 11 render workers,
300 maximum iterations, two warm-up runs, and five measured runs. Times below
are medians from the dedicated `benchmark` Maven profile.

## Median total speedup

| Render size | Tile | Short pan (95% reused) | Medium pan (75% reused) | Near-full pan (25% reused) |
|---|---:|---:|---:|---:|
| 1920x1080 | 16 | 10.06x | 3.96x | 1.19x |
| 1920x1080 | 32 | 12.31x | 5.28x | 1.19x |
| 1920x1080 | 64 | 11.73x | 5.51x | 1.22x |
| 2560x1440 HiDPI | 16 | 12.08x | 4.54x | 1.20x |
| 2560x1440 HiDPI | 32 | 13.96x | 5.68x | 1.24x |
| 2560x1440 HiDPI | 64 | 18.62x | 6.84x | 1.23x |

## Representative tile-32 timings

| Render size | Pan | Fresh total p50 | Reuse total p50 | Reuse total p95 | Calculated tiles fresh/reuse |
|---|---|---:|---:|---:|---:|
| 1920x1080 | Short | 54.89 ms | 4.46 ms | 6.72 ms | 2040 / 102 |
| 1920x1080 | Medium | 53.36 ms | 10.11 ms | 10.48 ms | 2040 / 510 |
| 1920x1080 | Near-full | 27.10 ms | 22.78 ms | 23.24 ms | 2040 / 1530 |
| 2560x1440 HiDPI | Short | 97.04 ms | 6.95 ms | 7.32 ms | 3600 / 180 |
| 2560x1440 HiDPI | Medium | 97.62 ms | 17.20 ms | 18.41 ms | 3600 / 900 |
| 2560x1440 HiDPI | Near-full | 48.72 ms | 39.28 ms | 39.98 ms | 3600 / 2700 |

## Conclusions

- Frame reuse is decisively effective for ordinary short and medium pans.
- The benefit scales with overlap: reusing 95% of a frame produced a 10-19x
  speedup, while reusing only 25% produced about a 1.2x speedup.
- Calculated tile counts track the missing image area closely, confirming that
  the speedup comes from avoided calculation rather than measurement artifacts.
- Tile size 64 produced the best median total times in most configurations, but
  its time to the first freshly calculated tile was less consistent. Tile size
  32 remains the default as a balanced choice for progressive responsiveness.
- Core colorization becomes a significant fraction of near-full pan time, so
  future optimization should avoid recoloring pixels that can be shifted from
  the displayed image.

These measurements exclude JavaFX scheduling, PixelBuffer upload, and the
displayed-image shift. A UI-level benchmark is still required before making
claims about end-to-end presentation latency.
