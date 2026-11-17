# Deterministic deep Mandelbrot base rendering (P2.7)

> Publication: reviewed summary only. Raw campaign directories, logs and the
> exploratory probe stay private; see [evidence provenance](../docs/development/PUBLICATION.md).

Roadmap item: [5.1, "Make deep Mandelbrot base rendering deterministic"](../ROADMAP.md#51-completed-render-retention-and-persistence-p27).
Decision: **retain** the coordinate-determined recovery references and the
scalar escape-uncertainty check as correctness fixes. The paired headless
check (C3) is **inconclusive** on one control with a 2% median difference; no
timing improvement is claimed. In the application pipeline (JavaFX
publication), completion passes non-inferiority on the BLA-active and
glitch-stress scenes and first visible publication keeps equal medians with
inconclusive intervals; on the recovery-heavy antenna the previous backend
produced a different frame on every render, so only an exploratory comparison
exists there (see [application measurement](#application-measurement)).

## Change

Before, `MandelbrotPerturbationRenderBackend` recovered a glitched pixel from
the nearest of up to 64 additional references, each built at whichever glitched
pixel a worker reached first, then from exact iteration once the 64 were used.
A pixel's value therefore depended on tile order and worker scheduling. Now a
glitched pixel uses the recovery reference of a fixed lattice point near it
(`RecoveryReferenceLattice`, 16 pixels apart, as the deep AA samplers already
do), then exact iteration. The lattice point at the primary reference reuses
the primary orbit instead of rebuilding it.

Independent review then found that a lattice reference could accept a wrong
escape decision where the primary reference had refused: on a 161x97 frame at
`-2.000000000000001`, `0`, scale `7e-15`, 2,688 iterations, `|z|^2` of the
first iterations lies within double rounding of 4, and the review counted 388
pixels differing from BigDecimal iteration against 222 for the previous
backend. Scalar perturbation, which recovery uses for base frames and deep AA,
now tracks a bound on the absolute error of `z` (delta propagation, stored
reference rounding and step rounding, using `|zr| + |zi|` instead of a square
root) and treats an escape decision inside that bound as unreliable, so the
pixel goes to exact iteration; a pixel exactly on its reference uses the
reference's exact escape margin. On long chaotic orbits the worst-case bound
soon exceeds the state although perturbation stays correct, so the check stops
once the bound exceeds 1e-12 of `|z|` and the glitch criterion governs as
before. The exact fallback also normalizes zero components, whose scale
otherwise grew every iteration on the real axis and made exact iteration of an
axis row take minutes. `CALCULATION_VERSION` and `ANTIALIAS_VERSION` of stored
frames became 3, so earlier deep entries are recalculated.

A second review found that the exact-margin shortcut trusted a rounded offset:
in a 3x3 frame at `-2 - 1e-330`, `0`, scale `8e-330`, 16 iterations, a deep AA
sample a third of a pixel right of the centre has an offset that converts to
`0.0`, and the candidate reported escape at iteration 1 where exact iteration
and the previous backend run all 16 iterations. Callers now establish "exactly
on the reference" from exact coordinates, the deep AA sampler sends an offset
that loses pixel resolution as a double to the scaled-delta loop as base frames
already did, and a recovery offset that loses it goes to exact iteration. This
follow-up changes no sample of the C3 fixtures, whose offsets keep resolution,
and was not part of the C3 builds.

A third review found the scaled-delta loop deciding an uncertain sign: in a 3x3
frame at `-2 - 3e-330`, `0`, scale `18e-330`, the AA sample a third of a pixel
right of the centre is exactly `c = -2`, whose orbit `0, -2, 2, 2, ...` never
escapes, but the reference margin and the linear term cancelled to a positive
residue and the sampler reported escape at iteration 1. The residue came mostly
from `ScaledValue.fromBigDecimal`, which used `Math.pow` with a fractional
exponent and had a relative error near 1e-13; it now scales by an exact power
of two and rounds once. The scaled loop also bounds the error of its delta like
the scalar loop and treats an escape decision within that bound as unreliable,
so the point goes to exact iteration. Base frames use the same loop beyond
double range. Exploratory 400x300 frames at `-2 - 1e-69`, scale `4e-330`, 300
iterations, and at the `deep-glitch` centre, scale `1e-320`, 2,700
iterations, kept iteration fingerprints equal to the previous backend.

A fourth review found the new error update scaling the step's delta with the
input exponent after the delta had been overwritten: at `-2 - 2e-323`, scale
`12e-323`, where the subpixel offset is subnormal instead of zero, the bound
became infinite on the first iteration, the check switched off and `c = -2`
escaped again. The update now uses the input delta, and the regression test
covers this frame too. The same overflow had also switched the check off for
many pixels of the earlier screening frames, so their timing is discarded. In
`fallsBackToHighPrecisionWhenPerturbationDeltasUnderflow` (`c = 2`, scale
`1e-400`, 5x3) the two pixels directly above and below `c = 2` now go to exact
iteration: their linear term cancels and `|z|^2 - 4` is the square of the
delta, far below the rounding bound of the stored reference; exact iteration
escapes them on the first iteration as before.

Screening with the check active (400x300, eight workers, one JVM per build, last
two of three renders): at `-2 - 1e-69`, scale `4e-330`, 300 iterations, both
builds take 26-40 ms; at the `deep-glitch` centre, scale `1e-320`, 2,700
iterations, the first version of the check took about 770 ms against 590 ms
for the previous backend, about 30% longer. It kept an absolute error in units
of the delta's exponent, which cost three extra rescalings per step. The check
now keeps the error relative to the delta and bounds the step's contributions
from the already aligned terms of the new delta, since `2 Z delta` and
`delta^2` cannot cancel in magnitude.

A fifth review found that this relative bound overflowed: on long orbits the
bound relative to a sub-double delta passes the double range while the
absolute error is still near 1e-165, and `(2 + r) r` overflowed before its
multiplication by the small quadratic term, so on the `1e-320` frame every
pixel stopped checking at iteration 561-562 of an orbit escaping at 1,755. An
earlier figure of about 6% was measured in that state and is discarded. The
relative bound is now a mantissa with its own exponent, rescaled in steps of
2^256 and multiplied in an order that cannot overflow while the absolute error
is informative; the informative limit compares the absolute error through
binary exponents instead of an underflowed double.
`scaledErrorBoundStaysTrackedWhileItsAbsoluteErrorIsInformative` runs the
scaled loop for pixels of a 12x8 frame at that centre and scale and requires
that the bound is dropped only above an absolute error of 1e-12; with the
earlier ordering it fails at iteration 562.

On the same frame, in 12 alternating JVMs (`main`, candidate, candidate,
`main`, two renders each after a warm-up), the median is 726 ms against
635 ms, about 14% longer. An extra shortcut that decided clear escapes from
binary exponents before rescaling the error terms measured 710 ms against
602 ms and was dropped. Iteration fingerprints are equal and neither frame
needs exact iteration. This cost is confined to frames beyond
double range and is screening, not a paired campaign.

## Correctness

- `MandelbrotPerturbationRenderBackendTest.baseSamplesDoNotDependOnTileOrderWhenGlitchesNeedRecoveryReferences`
  renders the glitch-heavy antenna view (`-2`, `0`, scale `1e-14`, 160x96,
  2,688 iterations) with one worker, with the bottom half calculated before the
  top half in separate generations, and with four workers, and requires
  bit-identical samples. It failed on the previous backend (four workers
  differed from one worker at pixel 79: 1 versus 3 iterations).
- `escapeDecisionsWithinRoundingOfTheRadiusMatchHighPrecision` renders the
  review frame with one and four workers and compares every pixel with exact
  iteration; the antenna test above now compares its complete frame too. An
  exploratory full-frame count against exact iteration found 0 mismatches on
  the review frame, the 160x96 antenna frame and a 33x25 `deep-glitch` frame,
  with and without BLA.
- `preciseSamplerDoesNotTreatAnUnderflowedSubpixelOffsetAsTheReference`
  samples the second review's point and compares it with exact iteration; it
  failed before the follow-up.
- `preciseSamplerDoesNotDecideAnUncertainScaledEscapeAtTheAntennaTip`
  samples the third and fourth reviews' points at `c = -2` and compares them
  with exact iteration; each failed before its fix.
- `deep-glitch`, `deep-bla` and `deep-boundary` keep sample fingerprints equal
  to the previous backend at 1512x982 (C3 below).
- An earlier candidate without the escape check differed from the previous
  backend on the 1512x982 antenna view in 17,991 pixels by iteration count;
  exact iteration of 300 random differing pixels agreed with it at all 300 and
  with the previous backend at 268.
- Not resolved here: the BLA loop has no escape-uncertainty check,
  and the scalar and scaled checks stop on long chaotic orbits (see the open roadmap item
  "Decide uncertain escapes in deep Mandelbrot perturbation").

## Exploratory timings

Probe outside the repository, 1512x982, 12 workers, one warmup and three
measured renders per JVM. First screening: JVMs in order A, B, B, A
(A = previous backend, B = first candidate, before the escape check). Medians
of six renders:

| Scene | Iterations | A complete | B complete | A recovery orbits | B recovery orbits | B exact pixels |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `deep-glitch` (1.6e-13) | 2,488 | 1.72 s | 1.70 s | 1 | 1 | 3 |
| `deep-bla` (1e-30) | 2,700 | 128 ms | 122 ms | 0 | 0 | 0 |
| `deep-boundary` (1e-30) | 137 | 128 ms | 129 ms | 0 | 0 | 0 |
| antenna (`-2`, 1e-14) | 2,688 | 0.91 s | 0.40 s | 20–31 | about 6,030 | 47,295 |

On the antenna view the previous backend sorted and retried every additional
reference for each glitched pixel; the candidate tries one lattice reference,
builds about one orbit per lattice point and sends 3% of pixels to exact
iteration, which is cheap there. Parabolic (`0.2500001`, 20,000 iterations),
seahorse, Myrberg and other probed deep views had no recovery work or less
than 5% difference in completion (single renders). The final candidate builds
no recovery orbit on `deep-glitch` (it reuses the primary orbit, see C2) and
produced samples bit-identical to the first candidate on all four scenes.
Final candidate (escape check, no square root), one JVM, medians of three:
`deep-glitch` 1.66 s with 0 recovery orbits and 3 exact pixels, `deep-bla`
157 ms (first render 251 ms), `deep-boundary` 123 ms, antenna 0.83 s with
about 6,700 recovery orbits and 216,709 exact pixels (15%): at this scale the
pixel spacing is far below the double resolution of `|z|` near 2, so those
decisions genuinely need exact iteration. Intermediate variants measured on
the way were slower and are not retained: an unbounded strict check took
5.2 s on `deep-glitch` (its error bound overflowed and rejected long orbits),
and a square-root bound 2.05 s. These are screening numbers only.

## Paired campaigns

Protocol: [alternating build comparisons](BASELINE_PAIRS_BENCHMARK.md), new
`non_inferiority` purpose (controls only, B/A <= 1.05), policy
[`p27-deterministic-deep-base.json`](policies/p27-deterministic-deep-base.json):
fixtures `deep-glitch`, `deep-bla`, `deep-boundary` of matrix 9.1-v3 at
1512x982, 6 process pairs, 3 warmups, 5 samples. A = `main` at `002dd4e`;
B = frozen working tree of the candidate. Apple M3 Pro (12 cores, 18 GiB),
macOS 27.2.1, Homebrew OpenJDK 26.0.2, 4 GiB heap, GPU disabled, `caffeinate -di`
around the whole campaign. Thermal pressure was `nominal` before and after
every JVM; the power source was not recorded per run (AC power after the
campaigns).

C1 measured the first candidate, which rebuilt the primary orbit at the origin
lattice point. Its `deep-glitch` first region was slower (median 2.84 → 3.60 ms,
B/A 1.27, 95% interval 0.96–1.36); diagnostics traced it to that rebuild, after
which the same three glitched pixels near the centre went to exact iteration.
C2 measured the second candidate, which reuses the primary orbit there but had
no escape check:

| Fixture | Metric | A median | B median | B/A | 95% interval | Verdict |
| --- | --- | ---: | ---: | ---: | --- | --- |
| `deep-glitch` | `first_new_region_ms` | 2.93 | 2.93 | 1.010 | 0.914–1.064 | inconclusive |
| `deep-glitch` | `backend_ms` | 1,706.7 | 1,711.7 | 0.993 | 0.984–1.011 | pass |
| `deep-bla` | `first_new_region_ms` | 2.83 | 2.76 | 0.975 | 0.952–1.014 | pass |
| `deep-bla` | `backend_ms` | 121.2 | 113.6 | 0.934 | 0.885–1.008 | pass |
| `deep-boundary` | `first_new_region_ms` | 2.34 | 2.32 | 0.997 | 0.959–1.021 | pass |
| `deep-boundary` | `backend_ms` | 132.8 | 131.2 | 0.979 | 0.936–1.010 | pass |

C2 aggregate: inconclusive (5 of 6 controls pass). C3 measured the final
candidate with the same policy and the same A build:

| Fixture | Metric | A median | B median | B/A | 95% interval | Verdict |
| --- | --- | ---: | ---: | ---: | --- | --- |
| `deep-glitch` | `first_new_region_ms` | 2.69 | 2.73 | 1.021 | 0.993–1.055 | inconclusive |
| `deep-glitch` | `backend_ms` | 1,630.6 | 1,658.2 | 1.017 | 1.008–1.025 | pass |
| `deep-bla` | `first_new_region_ms` | 2.71 | 2.72 | 0.999 | 0.930–1.018 | pass |
| `deep-bla` | `backend_ms` | 128.0 | 111.2 | 0.868 | 0.841–0.912 | pass |
| `deep-boundary` | `first_new_region_ms` | 2.26 | 2.31 | 1.013 | 0.991–1.046 | pass |
| `deep-boundary` | `backend_ms` | 129.3 | 126.8 | 0.993 | 0.957–1.029 | pass |

C3 aggregate: inconclusive (5 of 6 controls pass); thermal pressure `nominal`
before and after every JVM; sample fingerprints of both builds are identical
per fixture. `deep-glitch` completion costs about 1.7% (escape-bound tracking in
scalar steps), inside the limit. Its first region is about 0.04 ms (2%) later
by median, with the interval upper bound 1.055 just above the 1.05 limit; a
larger predeclared campaign would be needed to pass it formally. The
`deep-bla` completion gain is not claimed as an improvement: it was not a
declared target and has no explained mechanism. Headless timings end at returned
backend results, not at JavaFX publication; the antenna view is not in matrix
9.1-v3 and has no headless paired measurement. Windows and Linux are not
measured.

## Application measurement

Harness: [`BaselineFxBenchmark`](BASELINE_FX_BENCHMARK.md), which renders through
the production `FractalRenderService` into a visible `FractalSurface` and
times from the render request to the first visible publication
(`first_visible_publish_ms`) and to the complete publication
(`full_publish_ms`); after the timed repetitions it requires the last
repetition's samples and ARGB pixels to match a separate same-grid CPU render.
Matrix 9.1-v4 adds only the fixture `deep-antenna` (`-2`, `0`, scale `1e-14`,
fixed 2,688 iterations) to 9.1-v3. Build A is `main` at `002dd4e` with only
the 9.1-v4 harness files (`BaselineFixtures.java` and the manifest) applied, so
its production code is unchanged; B is the reviewed candidate. Both are frozen
working trees. FAST mode (the production mode for deep frames), 1512x982,
3 warmups and 5 samples per JVM, same machine and JDK as above, `caffeinate -di`
around everything, ES2 Prism pipeline on the active desktop.

The first paired campaign included `deep-antenna` and stopped at the first A
JVM: its antenna samples did not match the control render (first mismatch at
sample 808). The harness rejects this correctly; the conformance check was
not weakened. The paired campaign was rerun without that fixture, policy
[`p27-deterministic-deep-base-fx.json`](policies/p27-deterministic-deep-base-fx.json),
10 process pairs; thermal pressure was `nominal` before and after every JVM and
every last repetition of both builds matched its control:

| Fixture | Metric | A median | B median | B/A | 95% interval | Verdict |
| --- | --- | ---: | ---: | ---: | --- | --- |
| `deep-bla` | `first_visible_publish_ms` | 3.26 | 3.26 | 0.996 | 0.957–1.067 | inconclusive |
| `deep-bla` | `full_publish_ms` | 121.5 | 109.7 | 0.934 | 0.874–0.979 | pass |
| `deep-glitch` | `first_visible_publish_ms` | 4.78 | 4.41 | 0.938 | 0.707–1.273 | inconclusive |
| `deep-glitch` | `full_publish_ms` | 1,653.3 | 1,683.4 | 1.019 | 1.012–1.022 | pass |

Aggregate: inconclusive (2 of 4 controls pass, no control fails). First
visible publication takes 3-5 ms on both builds with equal or lower B medians;
its intervals are wide because single milliseconds vary with FX pulse timing,
and a formal pass would need a larger predeclared campaign. `deep-glitch`
completion costs about 1.9%, inside the limit, consistent with the headless
C3 result. As there, the `deep-bla` completion gain is not claimed.

Recovery-heavy `deep-antenna`, exploratory: 12 JVMs alternating A, B, B, A,
launched like the paired runner but without its campaign checks or thermal
observations; A timing rows were recorded before its failing verification.

| Build | Verification of the last repetition | Distinct sample hashes in 30 timed renders | `first_visible_publish_ms` median | `full_publish_ms` median (range) |
| --- | --- | ---: | ---: | ---: |
| A (`main`) | failed in all 6 JVMs (first mismatch at sample 808, 678,110, 678,115 or 684,160) | 30 | 5.90 | 1,027.7 (612.5–2,267.5) |
| B (candidate) | passed in all 6 JVMs | 1 | 5.89 | 860.1 (844.1–953.3) |

The previous backend produced a different frame on every render of this view,
so its timing describes different, partly wrong work and is no valid
non-inferiority control; the candidate produces one frame, matches its control
and completes in a narrow range. First visible publication is equal.
