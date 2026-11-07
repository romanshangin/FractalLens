# Spare frame storage: Retina fresh-frame allocation (2026-11-06)

**Decision: retain.** Campaign SF1 met both targets and passed memory, but it was
inconclusive on one pan timing family. A predeclared confirmatory pan campaign, SF2,
then passed every component. `WidePreviewSettings.DEFAULT` stays disabled in both
builds, and no other default changes.

## Change

`FractalRenderController.render` plans frame reuse on the JavaFX thread. When the
samples cannot be reused, for example on a zoom-out, `RenderFrame.create` allocated
a new frame of about 77 MB at 3024x1964 between the `render_start` and
`reuse_planned` marks. Most of that cost is kernel page-fault and zero-fill time.
The [fresh-frame decomposition](PRISM_PIPELINE_RESULTS.md#fresh-frame-planning-decomposition-diagnostic-2026-10-29)
had already traced profiled outliers to this allocation in `FractalData`.

`render/SpareFrameStorage` keeps at most one spare: empty frame storage, allocated
on a daemon thread, for the latest stable frame shape (width, height and the
orbit-trap storage flag).

- **When a spare is prepared:** after a frame took the spare, after two consecutive
  frames of one shape, and after a render completes. A changing shape, such as a
  resize drag, prepares nothing.
- **Storage is never recycled.** Each spare is newly allocated and handed to exactly
  one frame through `AtomicReference.getAndSet`, and nothing references it
  afterwards. It therefore cannot alias any of these:
  - the active, retained or cached frames;
  - the surface;
  - the AA service;
  - export;
  - a cancelled render worker that is still writing.
- **Planner change:** the controller now creates a target frame only for the chosen
  reuse source. Previously the cache-hit path first allocated a fresh frame and
  then discarded it. A source with no ready pixel inside the shifted target, for
  example after a pan past the frame edge or over an unrendered frame, now creates
  no candidate frame, so the spare goes to the visible fresh frame.
- **Cost:** one extra frame of live memory while a spare waits, about 77 MB at
  3024x1964.

Recycling unreferenced storage was rejected. It would need a lease across about
seven owners, and some of them are cancelled workers whose writes cannot be bounded
cheaply.

## Environment and builds

| Item | Value |
| --- | --- |
| Hardware | Apple M3 Pro, 12 cores, 18 GiB memory |
| OS | macOS 27.2, aarch64 |
| Java / JavaFX | 26.0.1+8-34 / 26.0.2+3, Metal Prism, CPU rendering, GPU disabled |
| Power / thermal | AC power and `nominal` thermal pressure before and after every process in both campaigns |
| A | Git `ca62810` (`main`), frozen by `prepare_baseline_build.py` |
| B | the candidate working tree on `fix/retina-frame-allocation-latency` (base `ca62810`), frozen with `--working-tree` |

Both builds have identical dependencies and driver classes; B only adds its own
`SpareFrameStorage*` tests. The driver is `WidePreviewInputBenchmark` with
`-Dwide.preview.settings=DEFAULT` for both labels. Each process ran at 3024x1964
with FAST and REFINED, one cold sequence, 3 warmups and 5 measured sequences,
-Xmx4g, and a private tmpdir and JavaFX cache.

Both policies were hashed before their first process. SF1 was declared at
2026-11-06T17:06:03Z and SF2 at 2026-11-07T00:07:50Z. The raw process output,
logs, policies and analyzers stay private.

## Rule

The unit is the per-pair group ratio `d = ln(median B / median A)`.

- **Timing:** [timing gate v2](TIMING_GATE_V2.md) with the owner limits.
- **Memory:** [memory gate v2](MEMORY_GATE_V2.md) on per-process peak RSS, limit
  1.05.
- **SF1 target:** wide-zoom-out `first_input_to_preview_post_layout_ms` for each
  mode. The upper bound of a seeded pair-bootstrap 95% interval must be <= 0.90.
- **Pair count:** neither campaign ran the A/A calibration stage. SF1 fixed N = 20.
  SF2 fixed N = 50 from a power estimate made on SF1's own pairs (below).

## SF1: pan-25 and wide-zoom-out, 20 pairs — inconclusive

| Component | B/A | 95% interval | Verdict |
| --- | ---: | --- | --- |
| Target, FAST zoom-out preview after layout (A median 16.5 ms) | 0.678 | 0.614–0.748 | pass |
| Target, REFINED zoom-out preview after layout (A median 16.0 ms) | 0.676 | 0.593–0.771 | pass |
| Timing overall average, 16 primary groups | 0.959 | 0.932–0.988 | pass |
| Family: pan-25 preview after layout | 1.012 | 0.952–1.072 | pass |
| Family: pan-25 first AA | 1.068 | 1.001–1.138 | **inconclusive** |
| Family: pan-25 first target after layout | 1.064 | 1.007–1.120 | pass |
| Family: pan-25 last post-layout | 1.056 | 0.991–1.119 | pass |
| Family: wide-zoom-out preview after layout | 0.677 | 0.617–0.740 | pass |
| Family: wide-zoom-out first AA | 1.008 | 0.986–1.030 | pass |
| Family: wide-zoom-out first target after layout | 0.865 | 0.826–0.900 | pass |
| Family: wide-zoom-out last post-layout | 1.001 | 0.991–1.012 | pass |
| Groups (28) | largest estimate 1.096 (pan FAST preview before layout, diagnostic) | — | all pass |
| Memory G1 median peak RSS | 1.010 | 0.993–1.024 | pass |
| Memory G2 P90 peak RSS | 0.998 | 0.971–1.029 | pass |

The zoom-out preview after layout dropped from about 16.5 ms to about 11.2 ms. The
FAST first target after layout fell by a quarter: the group estimate is 0.749,
interval 0.689–0.815.

**Deviation.** The owner paused the run, and its controller was lost with the
session. Pair 08's A process had finished, but it had no result record (peak RSS
and after-observation). That output is kept unchanged and excluded from the
analysis. Pair 08 was rerun from A in the declared order, and pairs 00–07 were
reused unchanged. An unrelated low-CPU Java process belonging to the owner was
running on the machine during at least the resumed part of the run.

## SF2: confirmatory pan-25, 50 pairs — pass

SF2 was declared after SF1 had been analyzed. Its command line differed from SF1's
only in `-Dbaseline.fixtures=pan-25`, and it used only fresh pairs, with no pooling
and no early stop.

Resampling SF1's pan pairs estimated the chance of a pass:

- if SF1's pan effect were real: 0.38 at 20 pairs, 0.65 at 40 and 0.80 at 50;
- for identical builds: 1.00 at 40 pairs or more.

| Component | B/A | 95% interval | Verdict |
| --- | ---: | --- | --- |
| Overall average, 8 primary groups | 1.024 | 1.005–1.046 | pass |
| Family: preview after layout | 1.022 | 0.997–1.050 | pass |
| Family: first AA | 1.026 | 1.006–1.047 | pass |
| Family: first target after layout | 1.027 | 1.002–1.054 | pass |
| Family: last post-layout | 1.022 | 1.002–1.044 | pass |
| Groups (14) | estimates 1.015–1.034 | — | all pass |
| Memory G1 median peak RSS | 1.001 | 0.997–1.011 | pass |
| Memory G2 P90 peak RSS | 1.003 | 0.998–1.011 | pass |

SF2 confirms a small pan cost of about 2–3%. Its interval excludes 1, so the cost
is real, but it is well inside the owner's 13% average and 25% group limits. It is
smaller than SF1's 5–7% point estimates.

The cause was not isolated. One plausible cause is the next spare's background
allocation running while the pan copies samples. A pan takes the spare, so the
next one is requested at once.

Read the two campaigns separately. SF2 is a second look, declared after SF1's pan
result was seen. For a borderline build this raises the retain probability above
what one fixed-size campaign would give. The fresh pairs and the fixed N limit that
effect.

## Descriptive: planning on the JavaFX thread

The table gives medians of `render_start` -> `reuse_planned` per label and cell,
with the P90 in parentheses: 100 measured renders per label and cell in SF1, 250 in
SF2. These numbers are not
gated.

| Cell | A | B |
| --- | ---: | ---: |
| SF1 wide-zoom-out FAST | 9.84 ms (18.21) | 2.80 ms (4.25) |
| SF1 wide-zoom-out REFINED | 10.65 ms (18.23) | 2.83 ms (4.07) |
| SF1 pan-25 FAST | 15.00 ms (22.83) | 16.47 ms (24.00) |
| SF1 pan-25 REFINED | 18.08 ms (33.68) | 15.99 ms (23.20) |
| SF2 pan-25 FAST | 15.22 ms (21.18) | 17.22 ms (22.73) |
| SF2 pan-25 REFINED | 15.36 ms (23.15) | 15.60 ms (22.47) |

A pan is dominated by copying about 75% of the samples, which costs 11–15 ms in a
standalone microbenchmark. The spare does not change that. Cutting the copy cost
is a separate question.

In the tables, "first AA" is `last_input_to_aa_ms`, "preview after layout" is
`first_input_to_preview_post_layout_ms`, "first target after layout" is
`last_input_to_first_target_post_layout_ms` and "last post-layout" is
`last_input_to_post_layout_ms`.

## Measured candidate and the later planner fix

Both campaigns measured the frozen build B. After review, the planner was changed
so that a reuse source without a ready pixel inside the shifted target no longer
creates a candidate frame first: before the fix such a candidate took the spare and
was discarded, and the visible fresh frame was allocated on the JavaFX thread. In
both measured fixtures, every reuse candidate had ready overlapping pixels: the
wide-zoom-out step changes pixel spacing, so no candidate is planned, and each
pan-25 step overlaps a complete frame. On pan-25 the new check runs but returns
at once for a complete source and changes no decision; the changed decision is never
taken on the measured paths, so its effect was not measured. For a partial source
the check scans the validity mask at most once: about 0.04 ms in a 3024x1964
microbenchmark with only the bottom tile row ready and the top 75% overlapping. Its
regression tests are listed below.

## Correctness evidence

These tests compare frames and pixels, not callbacks:

- `SpareFrameStorageTest`:
  - spare handoff, shape key and orbit-trap flag;
  - preparation coalescing and close;
  - a changing shape prepares nothing;
  - distinct storage after simulated late writes.
- `SpareFrameStorageReuseTest`:
  - pans in eight directions, zoom out and in, retained return, iteration-limit
    change, resize up and down, and retained AA;
  - each frame matches a fresh frame on the same grid exactly, in samples and ARGB,
    and shares no storage with any earlier frame;
  - a zoom-out cancelled after its first tile, followed by late writes into the
    cancelled frame, leaves the next frame exact;
  - a pan past the frame edge and a pan over an unrendered frame create only the
    visible frame, and that frame takes the spare.
- `ValidityMaskTest`: the ready-pixel check at every region edge, and against a
  pixel-by-pixel search for 2000 random masks and regions.
- `SpareFrameStorageFxTest`, opt-in, FAST and REFINED:
  - the same sequence through the production view's installed input handlers,
    including a cancelling replacement wheel, compared pixel by pixel with exact
    controls;
  - every gesture that should take the spare does.

Every campaign process also matched the control's sample and ARGB fingerprints.

## Limits

- One Apple M3 Pro at 3024x1964. Not measured: other sizes including 1512x982,
  other fixtures, long sessions, GPU, native fullscreen, Intel/AMD Macs, Windows
  and Linux.
- The memory gate passed, but the spare holds about 77 MB extra while it waits.
  Machines with little memory were not measured.
- No A/A calibration stage preceded either campaign.
