# Uncertain escape decisions in deep Mandelbrot perturbation (P2.7)

> Publication: reviewed summary only. Raw campaign directories, logs and the
> exploratory probes stay private; see [evidence provenance](../docs/development/PUBLICATION.md).

Roadmap item: [5.1, "Decide uncertain escapes in deep Mandelbrot perturbation"](../ROADMAP.md#51-completed-render-retention-and-persistence-p27).
Decision: **retain** as correctness fixes. Completion passes non-inferiority
on `deep-glitch` and is inconclusive on `deep-bla`; first new region is
inconclusive on both (see [paired campaign](#paired-campaign)). No timing improvement is claimed
from the paired campaign; the antenna view became much faster in exploratory
measurement because far fewer pixels need exact iteration.

## Findings

Full-frame comparisons with BigDecimal iteration at the grid precision (the
exact fallback) on 161x97 frames of the deep matrix fixtures, before the change:

- **Reference escape margin rounded to zero.** `ReferenceOrbit` rounded
  `|Z|^2` to the grid precision before subtracting 4. At the antenna view
  (`-2`, `0`, scale `1e-14`) the reference pixel is `c = -2 - 2e-59 i`, whose
  `|c|^2` exceeds 4 by `4e-118`: exact iteration escapes it at iteration 1,
  the rounded margin was 0, and the frame showed it escaping at 62.
- **BLA loop without an escape check.** With the reference exactly `c = -2`
  (`-2`, `0`, scale `1e-30`, 41x101), whose orbit stays on the radius, the BLA
  loop decided `c = -2 + 4e-31 i` (escapes at iteration 1) at iteration 40. BLA
  blocks also skip the escape test of every iteration they cover.
- **Error bound dropped at a few 1e-12.** At the `deep-boundary` centre, the
  error bound of an accurate, non-chaotic orbit is 1e-12 to 8e-12 at its
  escape, above the former informative limit of 1e-12, so decisions there were
  not checked. Newton-placed pixels (21x11 frames near `0 + 1i`, scale
  `1e-30`, 137 iterations) with `|z|^2 - 4 = ±1e-20` at their last iteration,
  which exact iteration at the grid precision resolves and a perturbed `|z|^2`
  does not, were wrong in 18 of 32 cases.
- **Chaotic orbits (not changed).** On `deep-glitch` 9 of 15,617 pixels differ
  from exact iteration by 1 to 180 iterations. Traces show the perturbed orbit
  matching exact iteration to about 1e-16 for about 1,700 iterations, then
  leaving the reference and diverging during roughly 250 expanding iterations
  before escape. This is the limit of double iteration, not an escape decision
  near the radius: direct double rendering shows the same effect (seahorse,
  scale 0.024, 3,000 iterations: 54 of 15,617 pixels differ from exact
  iteration). On the real axis right of `-2` exact iteration itself differs
  between the grid precision and three times that precision.

## Change

- The reference margin `|Z|^2 - 4` is computed from the squares rounded to the
  grid precision, as exact iteration compares them, with an exact sign.
- An escaped reference orbit continues while `|Z| <= 4`. Pixels next to a
  reference that escapes by a margin far below the pixel spacing follow it for
  an iteration or two; without this, the correct margin made the reference end
  at its first iteration and sent `c = 2` (scale `1e-400`) pixels to exact
  iteration (96 to 7,856 exact pixels at 161x97). BLA uses the last bounded
  iteration.
- Near the radius, a decision inside the error bound is first settled from the
  exact reference margin plus `2 Re(conj(Z) delta) + |delta|^2`, which keeps
  the resolution of the delta; only a decision that this cannot settle either
  goes to recovery or exact iteration.
- The BLA table gives no radius to a step whose reference lies within what a
  delta inside the radius could move across the escape radius, and bounds each
  block's approximation error (`deltaErrorFactor |delta| + deltaCErrorFactor
  |deltaC|`). A BLA pass that tracks the error bound decides as the scalar loop
  does and takes a block only when its end is certainly inside the radius.
  Tracking cost about 13% on `deep-boundary`, also when merged into the same
  loop behind a flag, so an untracked pass runs first and hands a pixel to the
  tracked pass at the first escape test within `1e-4` of the radius, which
  contains every decision the tracked pass could question (79 of 1.48 million
  pixels at 1512x982 `deep-boundary`).
- The error bound stays informative up to 1e-6 of `max(1, |z|)` instead of
  1e-12. A recovery reference settles a pixel only while its own bound is
  informative; otherwise the pixel goes to exact iteration.
- Stored frames and AA: `CALCULATION_VERSION` and `ANTIALIAS_VERSION` became 4.

## Correctness

- After the change, the full-frame comparisons give 0 differing pixels on
  `deep-bla`, `deep-boundary` and `c = 2` (scale `1e-400`), 9 on `deep-glitch`
  (the chaotic pixels above, unchanged), and on the antenna view only pixels of
  the chaotic real-axis segment, where exact iteration is not stable itself.
  All 32 Newton-placed cases match exact iteration.
- Regression tests, each failing on `main`:
  `referenceEscapingWithinRoundingOfTheRadiusMatchesHighPrecision`,
  `escapeDecisionsNextToAReferenceOnTheRadiusMatchHighPrecision` and
  `blaEscapeDecisionsWithinRoundingOfTheRadiusMatchHighPrecision` (two
  Newton-placed views, with and without BLA) compare complete frames with exact
  iteration. The determinism tests moved to the antenna tip at scale `1e-30`,
  because the antenna view at `1e-14` no longer needs recovery references; the
  base-frame one also failed on `main` (pixel `(80, 15)`, 2 instead of 1).
- `recoveryReferencesSettleOnlyDecisionsWithinTheirErrorBound` renders a
  Newton-placed view where the primary reference leaves a pixel at
  `|z|^2 = 4 + 1e-20` to recovery and the recovery pass has lost its bound by
  then; it fails (91 instead of 90) when recovery results are accepted without
  an informative bound.
- `MandelbrotBlaTableTest.errorFactorsBoundEveryBlockAgainstExactPerturbation`
  applies 165 blocks of a slowly converging orbit (`-0.5 + 0.5i`) to deltas at
  0.9 of their radius and compares them with exact perturbation from the exact
  reference: the largest error is 0.09 of the bound (0.012 for blocks of at
  least 64 iterations), and the test fails with zero error factors.
- At 1512x982, `deep-glitch` and `deep-bla` keep sample and ARGB fingerprints
  equal to `main`. `deep-boundary` and the antenna view keep every iteration
  count and escape flag and the ARGB fingerprint; their sample fingerprints
  differ only in smooth values (1 pixel by 2.8e-14 on `deep-boundary`; on the
  antenna view 159,811 pixels by at most 8.8e-13), where the candidate decides
  from the reference margin what `main` sent to exact iteration.

## Paired campaign

Protocol: [alternating build comparisons](BASELINE_PAIRS_BENCHMARK.md),
`non_inferiority` (controls only, B/A <= 1.05), policy
[`p27-uncertain-deep-escapes.json`](policies/p27-uncertain-deep-escapes.json):
`deep-glitch` and `deep-bla` of matrix 9.1-v4 at 1512x982, 6 process pairs,
3 warmups, 5 samples. The summary requires equal A/B fingerprints, so the two
fixtures whose smooth values changed are measured separately below. A = `main`
at `78e784a`; B = frozen working tree of the candidate. Apple M3 Pro (12 cores,
18 GiB), macOS 27.2.1, Homebrew OpenJDK 26.0.2, 4 GiB heap, GPU disabled,
`caffeinate -di` around the campaign; AC power and thermal pressure `nominal`
before and after every JVM.

C3, the final candidate:

| Fixture | Metric | A median | B median | B/A | 95% interval | Verdict |
| --- | --- | ---: | ---: | ---: | --- | --- |
| `deep-glitch` | `first_new_region_ms` | 2.74 | 2.80 | 1.029 | 0.971–1.054 | inconclusive |
| `deep-glitch` | `backend_ms` | 1,663.4 | 1,675.6 | 1.008 | 0.991–1.014 | pass |
| `deep-bla` | `first_new_region_ms` | 2.69 | 2.79 | 1.043 | 1.012–1.065 | inconclusive |
| `deep-bla` | `backend_ms` | 108.4 | 108.1 | 0.995 | 0.963–1.052 | inconclusive |

C3 aggregate: inconclusive (1 of 4 controls passes, none fails); sample and
ARGB fingerprints of both builds are identical per fixture. Completion costs
under 1% on `deep-glitch` and is unchanged by median on `deep-bla`, with an
interval just above the limit. The first new region of `deep-bla` is about
0.1 ms (4%) later by median, with an interval entirely above 1.0, so a small
real cost is likely. Part of it is building the BLA table, whose error factors
raised a 2,700-iteration table from about 43 to 56 us in an isolated loop
(66 us before the error magnitudes used a square root instead of `hypot`); the
rest is not attributed. A larger predeclared campaign would be needed to pass
or fail the first-region controls formally.

Earlier candidates with the same policy and A build, not retained: C1 tracked
the bound inside the single BLA loop (completion B/A 1.009 on `deep-glitch` and
0.958 on `deep-bla`, both pass; first regions 1.020 and 1.028, inconclusive),
but cost about 12% on `deep-boundary` in exploratory runs, which led to the
separate untracked pass. C2 had that pass with `hypot` error magnitudes
(completion 1.002 pass on `deep-glitch`, 1.002 inconclusive on `deep-bla`; first
regions 1.041 and 1.045, inconclusive).

## Exploratory timings

`deep-boundary` and the antenna view (`deep-antenna`), same builds, sizes and
settings, 6 alternating JVM pairs (AB, BA, ...) with 3 warmups and 5 samples
each, medians of 30 renders, thermal pressure `nominal` before and after every
JVM. No interval or verdict is claimed.

| Fixture | Metric | A median | B median | B/A of medians |
| --- | --- | ---: | ---: | ---: |
| `deep-boundary` | `first_new_region_ms` | 2.46 | 2.46 | 1.00 |
| `deep-boundary` | `backend_ms` | 130.5 | 131.3 | 1.01 |
| `deep-antenna` | `first_new_region_ms` | 2.59 | 2.05 | 0.79 |
| `deep-antenna` | `backend_ms` | 872.4 | 163.4 | 0.19 |

On the antenna view `main` sent many pixels to recovery references and exact
iteration because its reference ended at its rounded escape; the candidate's
continued reference and margin-based decisions leave almost none (at 161x97,
28 exact pixels and 6 recovery orbits against 1,751 and 20). This speedup was
not a declared target and is not a paired result.

Headless timings end at returned backend results, not at JavaFX publication.
Windows, Linux and Intel/AMD Macs are not measured.
