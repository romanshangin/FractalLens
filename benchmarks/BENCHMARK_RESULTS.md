# Frame reuse benchmark results

> Historical evidence: results and “current source” describe the dated experiment, not validation of this checkout. Only [selected evidence](README.md) is included; “private archive” entries are unavailable here. Original SHAs/commands may require private revisions or inputs. See [provenance and replay limits](../docs/development/PUBLICATION.md).

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

- Reuse is effective for short/medium pans.
- Overlap scales gains: 95% → 10-19x, 25% → about 1.2x.
- Calculated tiles track missing area, supporting avoided-work rather than artifact gains.
- Tile 64 usually wins median total but first-tile latency varies; default 32
  balances progressive responsiveness.
- Near-full pans spend substantial time coloring; avoid recoloring shiftable pixels.

These measurements exclude JavaFX scheduling, PixelBuffer upload, and the
displayed-image shift. A UI-level benchmark remains required before making claims about
end-to-end presentation latency.
