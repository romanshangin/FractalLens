# Complete-mask fast path for retained-frame returns

**Decision, 2026-11-11: retain, narrow scope.** The isolated readiness scans of a
fully reused frame drop from about half a second (1696x1188) and about five
seconds (3024x1964) to well under a millisecond; partial masks are unchanged.
This is an isolated scan measurement, not an interaction-latency or end-to-end
gate; the visible return time is left to the P2.7 Stage A verification.

This is a new experiment. It does not reopen or replace the rejected
[P0.3 bounded validity-mask candidate](VALIDITY_MASK_OPTIMIZATION.md), whose
general row-bounded scans and results stay as recorded.

## Problem

P2.7 Stage A returns to a fractal's retained frame: the base frame and its
displayed AA colors are restored complete, so neither calculation nor AA
sampling runs. Independent verification on 2026-11-11 (M3 Pro, 848x594 pt
Retina window, Antialiased display) still saw the loading placeholder for
0.98–1.04 s per compatible return, against 1.10 s for a fresh render. JFR placed
about 0.44 s in `ValidityMask.missingRowSpansSnapshot` (base planning) and about
0.43 s in `RefinedPixelSnapshot.isRegionRefined` (AA tile selection).

Both run once per 32x32 tile row and call the unbounded `BitSet.nextClearBit`,
which on a complete mask searches the whole ready suffix of the frame from every
row: the cost grows with the square of the pixel count.

## Candidate

- `ValidityMask` caches `isComplete()` under its monitor. Adding ready pixels
  keeps a complete mask complete; `clear`, `copyFrom` and shifts (through
  `clear`) forget or reset the answer; `copy` carries it. `missingRowSpans` and
  `isRegionReady` return at once for a complete mask, after region validation.
- `RefinedPixelSnapshot` (immutable) records completeness at construction, and
  `isRegionRefined` returns at once when complete, after region validation.
- Partial masks keep the original scans. A mask missing one pixel is therefore
  as slow as before (the case P0.3 targeted); it is not on the retained-return
  path, where restored frames are complete.

## Protocol

[RetainedReturnScanBenchmark](../src/test/java/com/shangin/fractal/render/RetainedReturnScanBenchmark.java)
times production `missingRowSpans` over a complete mask, `isRegionRefined` over
a complete refined snapshot, and, as the partial control, `missingRowSpans`
over a mask missing only its final pixel. Each covers the 32x32 tile partition
at 1696x1188 (an 848x594 pt window at 2x) and 3024x1964. Every sample asserts the
expected result (no missing work; exactly one missing span for the control).
Two warmups and five measured samples per scenario and process.

Builds were compiled from one working tree, before and after the candidate, and
run as six separate processes in the order A B B A A B (three per side, 15
samples per side and scenario). The benchmark bytecode was identical in both
builds; only `ValidityMask` and `RefinedPixelSnapshot` differed. The whole
campaign ran under `caffeinate -di`; no helper remained afterwards.

Code state: both builds were uncommitted working trees over `main` at
`44ed5bd` on the P2.7 Stage A task branch, so no commit identifies them.

- A was the Stage A candidate before the fast path. The SHA-256 of `git diff main`
  started `9b7051eb885facd7`.
- B was A plus this change to `ValidityMask` and `RefinedPixelSnapshot`, which
  the Stage A delivery is to contain.

Compiled-class SHA-256 prefixes:
- `ValidityMask.class`: A `5d7a93be7f594720`, B `4e8c0617b4e38019`.
- `RetainedReturnScanBenchmark.class`: `1e9b023a4cba703d` in both builds.

Reproduction, macOS/Linux, from the repository root. Snapshot each build's
classes after compiling it, then alternate the processes:

```sh
./mvnw -q -DskipTests test-compile
mkdir -p "$TMPDIR/scan-A" && cp -R target/classes target/test-classes "$TMPDIR/scan-A/"
# apply or remove the candidate, compile again and snapshot into "$TMPDIR/scan-B"
caffeinate -di bash -c 'i=0; for side in A B B A A B; do i=$((i+1));
  java -cp "$TMPDIR/scan-$side/test-classes:$TMPDIR/scan-$side/classes" \
    com.shangin.fractal.render.RetainedReturnScanBenchmark "$side-run$i" > "$TMPDIR/run$i-$side.csv"; done'
```

Use `;` as the classpath separator on Windows and a platform sleep inhibitor
instead of `caffeinate`. The summary takes the medians of the `sample` rows per
side, size and scenario.

Environment: Apple M3 Pro, 12 logical CPUs, 18 GiB RAM, macOS 27.2 (arm64),
Java 26.0.1, default JVM options. `pmset -g therm` reported no thermal or
performance warning before or after. Headless; no JavaFX.

## Results

Medians of 15 samples per side, in milliseconds.

| Size | Scenario | A (before) | B (candidate) | B/A |
| --- | --- | ---: | ---: | ---: |
| 1696x1188 | base, complete mask | 556.097 | 0.107 | 0.0002 |
| 1696x1188 | AA, complete snapshot | 554.109 | 0.075 | 0.0001 |
| 1696x1188 | base, last pixel missing (control) | 554.913 | 554.689 | 0.9996 |
| 3024x1964 | base, complete mask | 4875.389 | 0.074 | 0.00002 |
| 3024x1964 | AA, complete snapshot | 4876.444 | 0.043 | 0.00001 |
| 3024x1964 | base, last pixel missing (control) | 4875.119 | 4869.789 | 0.9989 |

Ranges: A 552–14432 ms (one 3024x1964 AA outlier of 14.4 s; all other A samples
within 553–4988 ms), B complete scenarios 0.007–0.126 ms, B controls 552–4994 ms.

## Correctness coverage

`ValidityMaskTest.cachedCompletenessFollowsEveryChangeAndCompleteMasksReportNoMissingWork`
covers completion by `markReady` and `markReadyCountingNew`, staying complete,
`copy`, shift, `copyFrom` of partial and complete masks, `clear`, and exact
missing spans one pixel before completion.
`RefinedPixelSnapshotTest.completeSnapshotsReportEveryRegionRefinedAndPartialOnesStillScan`
covers complete and partial snapshots and region validation. The existing
validity, AA, render-service and retention suites remain applicable.

## Limits

- Isolated scans only; the visible return latency, first publication and AA
  completion in the application still need the Stage A verification.
- One machine; other Macs, Windows and Linux are not measured.
- Partial masks keep their quadratic suffix scans, including the AA publication
  counting in `markReadyCountingNew`; those remain P0.3/9.3 territory.
- Raw CSV output is kept privately; this report publishes the summary only.
