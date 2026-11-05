# AA retained memory and sampling allocations

## Implementation and budget scope

AntialiasSampleCache reports a conservative retained-graph estimate rather than
only encoded phase payload. It charges map table capacity, worst-case tree nodes,
boxed keys, sample records, aligned phase arrays, owner metadata and one current
snapshot's arrays. Estimates allow 64-bit uncompressed references and object
alignment through 16 bytes. They are admission bounds, not a JVM object-size
instrument. Empty-owner/static lookup storage, worker-owned tile staging and
immutable snapshots retained by callers are outside this per-cache admission
budget. The cache releases its map/table on clear. Shifts preserve pixel LRU
order and replace the previous table. A smaller retained budget can evict more
AA samples; the existing base-color fallback during later palette recoloring
continues to apply. Exact completed AA display pixels are unchanged.

The final production change is cache accounting only. The primitive sample-buffer
and primitive Mandelbrot distance-estimate experiment described below was
rejected on RSS and removed from production. Its frozen build and source patch
remain in the private archive. Existing formula APIs, candidate detectors, direct
and deep AA sampling, and export math are unchanged in the final tree. The new
AaAllocationBenchmark remains a test-only diagnostic entry point.

## Predeclared comparison

This is an AA memory correction with an allocation-reduction target, not a
claimed CPU throughput improvement. Extend the alternating-build method in
BASELINE_PAIRS_BENCHMARK.md with an allocation target because its existing timer
schema cannot represent allocated bytes. Do not label an allocation-only result
as that timer protocol's PASS.

Freeze A from the post-review wide-preview tree before these AA edits; freeze B
from this candidate. Both have preview disabled by default. Identical Java,
dependencies, fixtures and external allocation-driver classes are required.
Keep historical wide-preview RSS results unchanged.

- Profiler-free JavaFX: six fresh AB/BA pairs, three warmups, five measured
  sequences and the driver's first sequence. Physical sizes 1512x982 and
  3024x1964; fixtures mandelbrot-aa-regular, julia-aa-regular and
  mandelbrot-overview, requested FAST and REFINED. Preserve every group.
- Primary allocation target: separately run AaAllocationBenchmark using the
  identical source/class in both builds, six fresh AB/BA pairs, three warmups,
  five samples and a first sequence. The two AA fixtures and the same physical
  sizes apply. Mandelbrot allocated_bytes upper paired 95% B/A <= 0.90 in each
  size; Julia allocation upper bound <= 1.05. It uses the JDK total-thread
  allocated-byte counter; measurement is AA-only, outside base calculation,
  and includes AA preparation/publication and allocations by worker threads.
- JavaFX controls: upper paired 95% B/A <= 1.05 for first visible publication,
  first AA tile, AA completion and full publication, wherever applicable,
  separately for every fixture/size/requested-mode group. Seeded 5000-resample
  hierarchical bootstrap over process pairs, then matching samples.
- Peak process RSS <= 1.05 of control in every profiler-free process pair.
  Fixed 4 GiB heap, CPU backends, Metal Prism, no JFR/NMT/AA profiling during
  timing. Capture process boundaries, power and thermal observations.
- The existing JavaFX validator must accept complete repetition matrices and
  exact sample/ARGB controls; allocation runs must match every final sample
  and ARGB fingerprint between builds, including warmups/first sequences.
- Separately inspect actual cache-owned object sizes with a diagnostic agent,
  including collision/growth cases, and active/detached heap checkpoints.
  Keep instrumentation out of profiler-free timing. Exercise cancellation,
  deep Mandelbrot/Julia, histogram/traps, retained AA, resize and palette
  cycling with regression/JavaFX tests and visible inspection.

Retain the sampling optimization only if allocation, numerical, lifecycle,
latency and RSS controls pass. A failed control is reject; missing or unresolved
coverage is inconclusive. Budget accounting is a correctness correction, not a
speedup claim. Preview promotion still requires its own declared input campaign.
Raw commands, frozen builds and unselected evidence stay in the private archive.

## Results: sampling experiment reject; cache accounting retained

Frozen A is the post-review wide-preview tree with the old AA implementation:
`182c799b9e4752b669d4fe01a697e59a4fa97b6ca065e4a1ca474d8c2c372500`.
Frozen B is the rejected primitive sample-buffer plus primitive Mandelbrot
candidate-distance implementation:
`cc2745a180d8520bfaf8bc3971b8638f86fdca4ef7a17f79df7258c88c6351cf`.
The buffer-only intermediate build was not measured. The final cache-only tree
is a different candidate and must not inherit the rejected build's speedup or
allocation claims.

All six allocation AB/BA pairs completed. Every group has 30 measured matched
samples; all first/warmup/final sample and ARGB fingerprints match. MB below
means decimal bytes / 1,000,000. The process-wide allocated-byte counter is scoped
to AA calls and includes their workers and preparation, outside base rendering.

| Fixture | Physical size | A median allocated MB | B median allocated MB | Median B/A | Paired 95% B/A interval |
| --- | --- | ---: | ---: | ---: | --- |
| Mandelbrot AA | 1512x982 | 200.1 | 37.3 | 0.1865 | 0.1863–0.1887 |
| Mandelbrot AA | 3024x1964 | 748.5 | 136.8 | 0.1827 | 0.1826–0.1847 |
| Julia AA control | 1512x982 | 365.2 | 166.1 | 0.4548 | 0.4546–0.5939 |
| Julia AA control | 3024x1964 | 1362.8 | 656.5 | 0.4818 | 0.4816–0.5952 |

These allocation targets/controls passed, but profiler-free JavaFX RSS failed:

| Completed process pair | A peak RSS bytes | B peak RSS bytes | B/A |
| --- | ---: | ---: | ---: |
| AB | 2088779776 | 2632941568 | **1.260517** |
| BA | 2394521600 | 2417836032 | 1.009737 |

The campaign was stopped after assessing the first hard RSS failure. The next
control process was intentionally cancelled and excluded. Both completed FX
pairs passed the existing complete-matrix validator and exact final sample/ARGB
controls. Their timing groups have only 10 measured matched samples instead of
30; timing acceptance is inconclusive. No CAMPAIGN_COMPLETE or latency promotion
is claimed. No limits were relaxed and no GC or heap flags were tuned to obtain
a passing result.

### Separate attribution

A separate instrumented replay used the same FX workload with JFR/NMT, GC logs
and heap/RSS sampling. Its results are not profiler-free timing evidence:

- A's maximum observed committed heap was about 2,412 MB; B expanded to the full
  4,295 MB allowed by -Xmx4g. Occupied heap after detached diagnostic GCs was
  about 36.6 MB in both. This supports heap/GC chronology as the source of the
  process peak difference rather than newly retained AA storage; it is not an
  indefinite leak-freedom proof or a complete attribution of all Metal memory.
- A base-render-only diagnostic found nearly identical allocated bytes in both
  builds (differences below 0.01% in the selected Mandelbrot/Julia cases).
- In a live 3024x1964 seahorse frame with preview explicitly enabled in both
  builds, cache accounting in A reported 27.5 MB for 102.1 MB of directly
  attributed cache objects. B reported 134.2 MB for 63.4 MB of cache objects;
  AA cache membership fell from 764,847 to 462,605 pixels under the corrected
  admission budget. Total occupied live heap was approximately 353/314 MB.
  These measurements describe the rejected B build, not an unmeasured promotion
  of the final cache-only tree.
- The final cache-only implementation is separately checked by a diagnostic
  javaagent using Instrumentation.getObjectSize. The old accounting fails the
  graph-underreporting regression. New accounting passes collision/treeification,
  growth, snapshots, shift and clear with 4096/8192/1048576-byte budgets, both
  normal compressed references and uncompressed references/class pointers with
  16-byte object alignment. The latter attributed maxima were 3488/6800/125616
  bytes, all within the charged budgets. No javaagent is added to production.

Environment: Apple M3 Pro, 12 logical CPUs, 18 GiB RAM, macOS 27.2 arm64,
Oracle JDK 26.0.1, JavaFX 26.0.2, CPU rendering and Metal Prism. Completed campaign
processes preserve power/thermal observations before/after; those observations
do not establish clocks, temperature or pressure throughout each process.

An initial preflight hit mismatched Swift compiler/SDK versions; selecting the
matching Xcode SDK resolved it before measurement. After the first allocation
pair, Python 3.9 rejected validation's zip(strict=True). Validation was corrected
to use zip after its existing explicit equal-length assertion. The unchanged
commands, frozen drivers and completed pair were validated/reused, not retried.
An earlier portable run hit a sandbox native-cache lock; a fresh task cache and
an unchanged desktop run passed. These environment/driver issues are preserved
privately and were not treated as product fixes.

### Final-tree self-validation and remaining scope

The formula/sampling optimization is archived and inactive. The cache budget
correction remains in the working tree. Preview stays disabled by default. At the
time of this run, its decision was the original v1 memory rejection; the later
[calibrated follow-ups](WIDE_PREVIEW_VALIDATION.md#follow-up-calibrated-memory-gate-v2)
replaced that rule and left the decision inconclusive. No production GC calls, global heap
policy, UI settings, dependencies, tile ordering or export math were changed.

The final cache-only build identity is
`a1ae3ded43385f7b4b9443139bddf5cac38ca6005709a2014ef0f0824085dffa`.
Its separate live ownership probe repeats the 3024x1964 seahorse source with
preview explicitly enabled on both sides. Corrected admission holds 462,605 AA
pixels versus 764,847 in control, charging 134.2 MB for approximately 63.4 MB of
attributed cache objects versus the old 27.5 MB charge for 102.1 MB. The primitive
sampling optimization is absent in this final build.

A live desktop inspection also used the final cache-only sources with an
explicit 8 MiB AA budget and Antialiased display. Start/stop Color Cycling changed
the palette, a real wheel zoom-out completed in 730 ms, and a real drag pan
completed in 176 ms. These are qualitative observations from the inspection,
not benchmark latency statistics. The inspection process was closed afterwards.

Developer self-validation uses the pinned `./mvnw clean test`, the opt-in
`WidePreviewFxTest,FractalResizeFxTest,RecolorFailureGateTest` suite with a fresh
JavaFX cache and `-DreuseForks=false`, publication-directory unit tests,
`git diff --check`, public-data checks and both history/tree secret scans.
Execution logs and final counts are retained privately with the handoff; skips
are reported separately. These are developer checks, not independent review or
verification.
Full new paired performance acceptance for the final cache-only tree, a global
RSS ceiling, a long-session soak, native GPU, Windows/Linux and optional native
fullscreen remain unverified. Enabling the allocation optimization is blocked
by the declared RSS criterion; reducing allocated bytes alone did not pass it.

## Follow-up: distance-only candidate

A separate follow-up keeps the corrected cache budget and removes only the
Mandelbrot candidate detector's temporary FractalSample/DistanceSample objects.
It does not introduce a primitive subsample buffer or change regular/deep AA
subpixel evaluation. A default distance-only capability preserves other formulas,
and subclass overrides still use their existing sample-returning methods. The
existing derivative loop and distance arithmetic are shared by both Mandelbrot
entry points. Unavailable estimates remain NaN.

Before execution, the follow-up adopted the same six AB/BA pairs, fixtures,
physical sizes, warmups, samples, allocation targets, exact fingerprints, latency
controls, per-process-pair RSS limit and fixed 4 GiB heap declared above. No GC
flags or acceptance limits changed. The control remains frozen A
`182c799b9e4752b669d4fe01a697e59a4fa97b6ca065e4a1ca474d8c2c372500`.
The distance-only candidate identity is
`40196cd0e66ce3d33475a2bbbeb07a59c2947a50c41bb7d176c755c9de3a8940`.
It is a new experiment; the rejected primitive-buffer results do not transfer.
The final decision is **reject** as detailed below. The distance-only changes
and their regression test were removed from the production candidate and saved
with the frozen build and patch in the private archive.

The six-pair follow-up completed every exact-control sequence. All allocation
controls and all six process-RSS controls passed; no latency group failed its
lower-bound criterion. However, 25 of 40 latency groups were inconclusive and
15 passed. The complete decision is **inconclusive**, not retain.

A predeclared extension therefore reuses those six FX pairs and all six
allocation pairs, adding 24 FX pairs for 30 process pairs/150 measured samples
per latency group. The code, fixed heap, flags, fixtures, modes, warmups,
repetitions, limits and bootstrap remain unchanged. Original policy/results are
preserved separately. The extension runs to its declared end unless an exact
control or hard RSS control fails; it cannot stop early for a favorable pass.
No allocation optimization is accepted while these controls remain unresolved.


### Extended comparison: reject on RSS

The extension stopped automatically after process pair 8 (the ninth completed
pair) exceeded the unchanged hard RSS criterion. All nine FX pairs passed the
complete-matrix and exact sample/ARGB controls, including first and warmup
sequences. Eight pairs stayed within 1.05; the failing pair was AB:

| Pair | Control peak RSS bytes | Candidate peak RSS bytes | B/A |
| --- | ---: | ---: | ---: |
| 8 | 2375024640 | 3282436096 | **1.382064** |

The original six-pair comparison remains separately recorded as inconclusive.
The extension has 45 measured matched samples per latency group, fewer than its
declared 150; it has no completed timing acceptance or EXTENSION_COMPLETE marker.
The hard failure is sufficient for reject; no favorable subset is promoted.
All six allocation pairs remain valid evidence for this rejected frozen build:

| Fixture | Physical size | A median allocated MB | B median allocated MB | Paired median B/A | Paired 95% B/A interval |
| --- | --- | ---: | ---: | ---: | --- |
| Mandelbrot AA | 1512x982 | 200.07 | 84.60 | 0.422848 | 0.422760–0.422851 |
| Mandelbrot AA | 3024x1964 | 748.53 | 284.04 | 0.379459 | 0.379292–0.379463 |
| Julia AA control | 1512x982 | 365.16 | 365.16 | 1.000003 | 0.999951–1.000004 |
| Julia AA control | 3024x1964 | 1362.78 | 1364.91 | 1.001556 | 1.001468–1.001558 |

The narrower variant therefore removes 58–62% of Mandelbrot AA allocation but
still fails process memory acceptance. Removing the primitive subsample buffer
alone is not a sufficient remedy for the earlier RSS rejection. This observation
does not independently attribute the new peak to a particular heap/native owner
or establish a leak. No GC policy, heap limit, thermal filter or threshold was
changed to obtain acceptance. Completed boundaries reported nominal thermal
pressure; power observations are retained privately. The environment and
frozen control are unchanged from the first experiment above.

The archived distance-only regression suite passed 26 targeted tests before
measurement. Those tests and four production edits belong to the rejected
experiment, not the final working tree. The final production tree again contains
only the pre-existing AA retained-budget correction and default-disabled wide
preview. The planned input-performance follow-up was not run because there is
no accepted allocation candidate to promote. The wide-preview RSS rejection
and implementation blocker remain open. Final portable/FX/publication checks
are recorded separately for this restored tree and do not convert reject to
performance acceptance.

## Follow-up: calibrated memory gate v2

The per-pair RSS rule above was later shown to reject identical builds with high
probability under this workload. A separately declared, A/A-calibrated gate
reran the distance-only build on 30 fresh pairs. Memory and 16 of 40 timing
groups were inconclusive, none failed, and the decision is **inconclusive**.
The build stays out of production. The v1 reject above remains its historical
result. See [memory gate v2](MEMORY_GATE_V2.md).

## Follow-up: default budget recalculation

The corrected accounting kept the old 128 MiB default. The independent review
found that this evicts AA samples that main retained even with the wide preview
disabled, so palette recoloring and Color Cycling would fall back to base colors
for those pixels. A budget rule was declared on 2026-11-05 before any
measurement:

- Measure full retention with the pre-recalculation build `56730450...` under an effectively unbounded
  budget, in the default configuration with the preview disabled. A probe seeded
  every AA-capable fixture through the production `FractalView` path in REFINED
  and FAST, at 1512x982 and 3024x1964, and waited for COMPLETE. It recorded cache
  entries, `usedBytes()`, and object sizes attributed with
  `Instrumentation.getObjectSize`.
- Choose the smallest multiple of 32 MiB that is at least 1.25 times the largest
  full-retention charge, up to a ceiling of 512 MiB.

| Fixture at 3024x1964 | AA entries | Charged at full retention | Attributed objects |
| --- | ---: | ---: | ---: |
| deep-glitch-aa | 1,717,611 | 451.4 MiB | 186.5 MiB |
| julia-overview / julia-aa-regular / julia-aa-retained | 790,990 | 221.2 MiB | 110.6 MiB |
| seahorse-adaptive | 764,847 | 206.4 MiB | 97.4 MiB |
| seahorse-fixed | 656,121 | 178.2 MiB | 84.1 MiB |

The largest charge was 451.4 MiB. With 25% headroom that is 564.3 MiB, which is
above the ceiling, so the rule selected no value. The owner chose **512 MiB**.
This retains every measured frame, with 13% headroom for the heaviest one.
Histogram and orbit-trap fixtures use no AA sample cache in this path.

A fresh freeze of the edited tree
(`7e2ec17b1b4cdb6a700d62d2c9975a65a976df7f4d52ee202a457f69efe6fa99`) passed every
predeclared acceptance check with the default budget and no property override:

- Every fixture, mode and size retained exactly the full-retention entry count.
  Every count equals the pre-correction control build
  `182c799b9e4752b669d4fe01a697e59a4fa97b6ca065e4a1ca474d8c2c372500`.
- Attributed bytes stayed within the charged bytes in every case. The charge is
  2.00–2.43 times the attributed size under the default compressed-reference
  layout.
- For every case with at least 10,000 entries, attributed bytes equal the
  control's (ratio 1.000). The default configuration therefore retains the same
  AA content as main on these frames.

The retained-graph bound remains real. 512 MiB charged corresponds to at most
about 210–256 MiB of default-layout cache objects. The pre-correction accounting
admitted about 3.7 million entries under 128 MiB, roughly 500 MB of objects. The
JVM heap is not configured explicitly, so its default maximum is a quarter of
physical memory.

A unit regression test fills the default cache with the heaviest frame's entry
mix and requires no eviction. It fails at the old 128 MiB budget, retaining
491,117 of 1,717,611 entries.

Not covered: displays larger than 4096 px per side, such as 5K or 6K fullscreen,
which the fixture matrix cannot express; process-RSS campaigns; GPU paths; and
visible palette-cycling inspection at the new default. The probe, policy and raw
measurements are kept in the private archive.
