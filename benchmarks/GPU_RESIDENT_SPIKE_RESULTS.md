# GPU-resident Mandelbrot feasibility spike

> Historical evidence: results and “current source” describe the dated experiment, not validation of this checkout. Only [selected evidence](README.md) is included; “private archive” entries are unavailable here. Original SHAs/commands may require private revisions or inputs. See [provenance and replay limits](../docs/development/PUBLICATION.md).

Date: 2026-09-05

Hardware: Apple M3 Pro, 12 logical processors

Software: Java 26.0.2, macOS 26.6.2, Vulkan 1.1.350 through MoltenVK

## Question and gate

Test avoiding full sample round trip: canonical Vulkan samples →reject indices
readback →exact CPU recovery/correction upload →GPU color →final ARGB only.

Predeclared gate: paired median end-to-end ≥15% faster than direct CPU/color
at both 1512x982/3024x1964 for overview/exterior/seahorse. Any failure blocks
production selection.

## Paired medians

Each row is the median of 10 measured pairs after 3 warmups. Allocation, axis
packing, synchronization, exact CPU recovery, correction upload, GPU coloring,
and final readback are included.

| Scene | Size | CPU, ms | Resident GPU, ms | GPU / CPU | Result | Certified |
|---|---:|---:|---:|---:|---:|---:|
| overview | 1512x982 | 12.225 | 12.170 | 0.995x | 0.5% faster | 91.81% |
| exterior | 1512x982 | 12.595 | 5.160 | 0.410x | 59.0% faster | 99.88% |
| seahorse | 1512x982 | 32.108 | 43.825 | 1.365x | 36.5% slower | 31.48% |
| overview | 3024x1964 | 44.590 | 26.813 | 0.601x | 39.9% faster | 91.80% |
| exterior | 3024x1964 | 49.551 | 7.976 | 0.161x | 83.9% faster | 99.89% |
| seahorse | 3024x1964 | 125.887 | 156.173 | 1.241x | 24.1% slower | 31.47% |

Retina seahorse: 4,070,274/5,938,136 rejected, 117.013 ms exact recovery,
almost full CPU baseline. Exterior: 6,553 rejects/0.177 ms recovery.

## Correctness and transfer observations

- All rejected pixels receive the direct-double sample and its CPU-derived
  palette phase before GPU coloring.
- Accepted colors differ from the direct CPU pipeline in fewer than 1% of pixels,
  with maximum error of one 8-bit channel level in every measured workload.
- Retina overview uses 152,606,188 native bytes and reads back 25,705,532 bytes;
  Retina exterior uses 142,992,328 native bytes and reads back 23,782,760 bytes.
- Retina seahorse expands to 224,266,748 native bytes and 40,037,644 readback
  bytes because of its correction list, illustrating why rejection density must
  be part of any residency policy.

Raw samples and cold/warmup rows are in
`GPU_RESIDENT_SPIKE_RESULTS.csv` (private archive: `GPU_RESIDENT_SPIKE_RESULTS.csv`).

## Decision

**No-go.** Residency overcomes transfer deficit with few rejects, but fails
15% cross-scene gate. CPU default/production GPU unchanged.

Future work must reduce recovery (stronger certificate/rejection-density early
fallback). No palette/pan/AA storage/parity layering until recovery bottleneck falls.

## Follow-up rejection profile

The resident rejection buffer was extended with reason counters and the seahorse
workload was repeated at both sizes. The distribution is deterministic across
measured frames:

| Size | Rejected | Uncertain escape interval | Smooth bound too wide | All other reasons |
|---|---:|---:|---:|---:|
| 1512x982 | 1,017,433 | 520,316 (51.14%) | 497,117 (48.86%) | 0 |
| 3024x1964 | 4,070,274 | 2,082,327 (51.16%) | 1,987,947 (48.84%) | 0 |

Zero categories: bounded/raw, iteration, invalid interval, palette-phase, catch-all.
Palette/quantization/dispatch tweaks cannot reduce recovery materially. Early fallback
can cap regression, but after full FP32 cannot deliver 15% seahorse gain.

Next justified compute experiment: higher-precision reject-only certificate,
same conformance/cross-scene gate before production. Private raw pairs:
`GPU_RESIDENT_REJECTION_PROFILE.csv` (private archive: `GPU_RESIDENT_REJECTION_PROFILE.csv`).

### Centered-error certificate attempt

Reject-only outward FP32 radius around raw orbit retained more correlation than
rectangles but certified zero extra pixels in three 384x256 scenes/both full
seahorse sizes.

3024x1964: calculation 27.06 →46.65 ms, same 4,070,274 CPU corrections.
Second pass removed; preserve `GPU_RESIDENT_SECOND_STAGE_PROFILE.csv`
(private archive: `GPU_RESIDENT_SECOND_STAGE_PROFILE.csv`). Future work needs
higher precision, not reshaped FP32 intervals.

### Double-single accuracy ceiling

Optimistic two-float reject recovery accepted every finite double-single before
rigorous stability certification, eliminating CPU recovery.

| Scene | Size | CPU, ms | GPU, ms | Result | Outliers > 1 | Max channel error |
|---|---:|---:|---:|---:|---:|---:|
| overview | 1512x982 | 12.579 | 16.184 | 28.7% slower | 10 | 33 |
| exterior | 1512x982 | 15.146 | 3.366 | 77.8% faster | 0 | 1 |
| seahorse | 1512x982 | 35.285 | 49.260 | 39.6% slower | 20 | 121 |
| overview | 3024x1964 | 47.015 | 45.005 | 4.3% faster | 46 | 114 |
| exterior | 3024x1964 | 52.016 | 7.915 | 84.8% faster | 0 | 1 |
| seahorse | 3024x1964 | 127.928 | 175.677 | 37.3% slower | 54 | 189 |

Emulation ceiling fails timing on both relevant scenes and conformance; stability/
exact fallback only adds work. Path removed; preserve
`GPU_RESIDENT_DOUBLE_SINGLE_RESULTS.csv` (private archive: `GPU_RESIDENT_DOUBLE_SINGLE_RESULTS.csv`).

This closes the current resident-calculation branch of 8.6. Further GPU render
work needs either efficient native FP64-class hardware or a different algorithm
whose performance can be demonstrated independently before production wiring.

Reproduction command:

```sh
JAVA_TOOL_OPTIONS='-Xmx2g -Dfractal.gpu.enabled=true \
  -Dfractal.residentBenchmark.warmup=3 \
  -Dfractal.residentBenchmark.samples=10 \
  -Dfractal.residentBenchmark.output=GPU_RESIDENT_SPIKE_RESULTS.csv' \
  mvn -Pgpu-resident-benchmark javafx:run
```
