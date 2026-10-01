# GPU-resident Mandelbrot feasibility spike

> Historical evidence: results and “current source” below refer to the dated
> experiment, not a new validation of this checkout. Only the
> [selected evidence](README.md) is included; entries marked “private archive”
> are unavailable in this repository. Original SHA values and recorded commands
> describe the original run and may require private revisions or inputs. See
> [provenance and replay limits](../PUBLICATION.md).

Date: 2026-09-05

Hardware: Apple M3 Pro, 12 logical processors

Software: Java 26.0.2, macOS 26.6.2, Vulkan 1.1.350 through MoltenVK

## Question and gate

The spike tests whether removing the full calculation-sample round trip can make
the existing certified FP32/CPU-recovery design competitive. It keeps canonical
samples in Vulkan storage, reads back only rejected pixel indices, calculates
those pixels exactly on CPU, uploads their corrections, colors on GPU, and reads
back only final ARGB.

The predeclared go/no-go gate is at least a 15% paired median end-to-end win over
the direct CPU render plus coloring at both 1512x982 and 3024x1964, across
overview, exterior, and seahorse scenes. Any failed scene keeps the spike out of
production backend selection.

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

The two Retina seahorse medians spend 117.013 ms in exact CPU recovery after
4,070,274 of 5,938,136 pixels are rejected. That recovery alone is close to the
complete CPU baseline. By contrast, the Retina exterior view rejects only 6,553
pixels and spends 0.177 ms in recovery.

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

**No-go for production integration.** The experiment proves that whole-frame
residency can overcome the earlier transfer deficit for low-rejection frames,
but it fails the cross-scene 15% gate. CPU remains the default and the existing
production GPU backend is unchanged.

A future experiment is justified only if it directly reduces correction-heavy
recovery—for example, a stronger certified kernel or an adaptive early fallback
based on rejection density. Palette residency, pan reuse, AA sample storage, and
feature-parity work should not be layered onto this spike before that bottleneck
is removed.

## Follow-up rejection profile

The resident rejection buffer was extended with reason counters and the seahorse
workload was repeated at both sizes. The distribution is deterministic across
measured frames:

| Size | Rejected | Uncertain escape interval | Smooth bound too wide | All other reasons |
|---|---:|---:|---:|---:|
| 1512x982 | 1,017,433 | 520,316 (51.14%) | 497,117 (48.86%) | 0 |
| 3024x1964 | 4,070,274 | 2,082,327 (51.16%) | 1,987,947 (48.84%) | 0 |

The zero categories are bounded/raw mismatch, iteration mismatch, invalid
interval, palette-phase mismatch, and the catch-all path. Consequently, palette
mapping, phase quantization, and dispatch tuning cannot materially reduce CPU
recovery. An early fallback can cap a future production regression, but after a
full FP32 pass it cannot produce the required 15% seahorse win.

The next justified calculation experiment is a second, higher-precision GPU
certificate applied only to FP32 rejections. It must keep the same conformance
contract and pass the existing cross-scene gate before any production wiring.
Raw diagnostic pairs are in
`GPU_RESIDENT_REJECTION_PROFILE.csv` (private archive: `GPU_RESIDENT_REJECTION_PROFILE.csv`).

### Centered-error certificate attempt

A second outward-rounded FP32 recurrence was then applied only to rejected
pixels. It tracked an error radius around the raw orbit to retain more
correlation than the rectangular interval. The result was unambiguous: it
certified zero additional pixels in all three 384x256 conformance scenes and at
both full-size seahorse resolutions.

At 3024x1964, the extra pass raised median calculation time from 27.06 to 46.65
ms while leaving all 4,070,274 CPU corrections in place. It was therefore
removed from the implementation. Measurements from the rejected prototype are
preserved in
`GPU_RESIDENT_SECOND_STAGE_PROFILE.csv` (private archive: `GPU_RESIDENT_SECOND_STAGE_PROFILE.csv`).
Further work needs a genuinely higher-precision GPU representation; another
FP32 interval reformulation is not justified by these results.

### Double-single accuracy ceiling

The next experiment emulated higher precision with a two-float orbit for every
FP32 rejection. It intentionally measured the optimistic ceiling before adding
a rigorous stability certificate: every finite double-single result was used,
so CPU recovery fell to zero.

| Scene | Size | CPU, ms | GPU, ms | Result | Outliers > 1 | Max channel error |
|---|---:|---:|---:|---:|---:|---:|
| overview | 1512x982 | 12.579 | 16.184 | 28.7% slower | 10 | 33 |
| exterior | 1512x982 | 15.146 | 3.366 | 77.8% faster | 0 | 1 |
| seahorse | 1512x982 | 35.285 | 49.260 | 39.6% slower | 20 | 121 |
| overview | 3024x1964 | 47.015 | 45.005 | 4.3% faster | 46 | 114 |
| exterior | 3024x1964 | 52.016 | 7.915 | 84.8% faster | 0 | 1 |
| seahorse | 3024x1964 | 127.928 | 175.677 | 37.3% slower | 54 | 189 |

The ceiling already fails performance on the two scenes that need emulation,
and accepting its results fails conformance. A stability certificate and exact
fallback would only add work. The double-single path was therefore removed;
raw samples are preserved in
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
