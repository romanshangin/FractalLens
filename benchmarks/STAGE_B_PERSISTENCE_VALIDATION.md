# P2.7 Stage B persisted-frame validation

**Status, 2026-11-16: the predeclared macOS performance and correctness
checks pass; one miss-overhead screening failure (deep 1696x1188, +10.7%)
passed its predeclared confirmatory rerun (+0.0%).
Uniform Deflate compression is rejected and the 1 GiB disk budget is
retained. The zero-candidate recolor limitation found here was fixed and
remeasured the same day (see below). Warm memory hits, disk hits and cold
misses were then compared on the same scenarios within one launch, and
every predeclared limit for them passed as well. Stage B was left open: a
display scale (DPI) change between launches was not checked, and Windows and
Linux are not validated.**

**Update, 2026-11-17: the DPI change between launches is covered by a JavaFX
test (see `ROADMAP.md`, P2.7 correctness acceptance), and the owner accepted
Stage B on macOS. Windows and Linux validation remains a separate open item;
the results in this report are macOS only.**

The checks measured the merged Stage B implementation on `main`
(`d5123d4`, frame and AA store `CALCULATION_VERSION` and
`ANTIALIAS_VERSION` 3). They are separate from the
[Stage A validation](STAGE_A_RETENTION_VALIDATION.md), which kept results
only within one process.

## Environment and method

Apple M3 Pro (12 cores), 18 GiB RAM, macOS 27.2.1 arm64, JDK 26.0.1 and
JavaFX 26.0.2, AC power, no thermal or performance warning recorded before
or after each run. Production CPU renderer (`fractal.gpu.enabled` unset,
GPU kernels off by default), Metal Prism, 2x output scale, 4 GiB Java heap
limit. The campaign controller ran under `caffeinate -di`; it verified both
display and system assertions before starting, an unlocked console session
and an awake display before and after each launch, and that the active Space
did not change. The assertions were gone after each run.

A private driver started one JVM per measurement and built the production
`MainView` over a temporary profile directory, so the frame store lived in
`frame-cache/` beside that profile's session file, as in the application.
Before the first launch of a profile the driver wrote the fixture scene as
the last session, with Wide Preview disabled unless stated. It then showed
the window at the given physical size and waited for the restored scene to
complete and the loading screen to release the surface. It recorded:

- **Render start to visible:** from the `render_start` latency event of the
  restored scene to its `complete` event (the loading screen is removed on
  `COMPLETE`, so this is the first visible frame). This is the interval the
  50% hit limit applies to.
- **Show to visible** (from `Stage.show`) and **process to visible** (from
  JVM start). About 200 ms of window creation and first layout before the
  render request, and about 1.0–1.1 s of JVM and JavaFX startup, are common
  to every condition.
- Base worker tasks from the render diagnostics, AA tested pixels and
  supersamples from the AA profile, SHA-256 fingerprints of the samples and
  of the displayed ARGB buffer, peak RSS (`/usr/bin/time -l`), the time of
  `MainView.close()` plus `Stage.close()` (which closes the store and waits
  for its pending writes) and the store's disk usage.

Each pair ran four launches: **off** (store disabled with
`-Dfractal.frameStore.enabled=false`, empty profile, then a change to the
Fire palette), **miss** (store enabled, empty profile), **hit** (the miss
profile again) and **hit + palette** (the miss profile with its original
session restored, then the Fire palette). The off and miss order alternated
between pairs. Pair 0 is a warmup and is excluded from the timing tables;
every pair, warmup included, entered the exactness checks.

Fixtures, all Mandelbrot with the Ice palette:

- **standard:** home view, 300 fixed iterations, Refined AA;
- **deep:** center `(0, 1)`, span `1e-30`, 137 fixed iterations, default
  deep mode Base only;

at 1696x1188 and 3024x1964 physical pixels. Additional single pairs covered
deep Julia (default constant, center `(-0.035, -0.493)`, span `1e-16`, 3000
iterations, deep Refined AA, 1696x1188) and the standard fixture with Wide
Preview enabled at both sizes.

### Predeclared limits

Declared before the campaign and accepted by the owner:

- A disk hit at restart (both sizes, standard and deep): median render start
  to visible at most 50% of the cold miss; zero base worker tasks, zero AA
  tested pixels and supersamples; samples and ARGB bit-identical to the miss
  and to the store-disabled run.
- A cold miss with the store enabled against the store disabled: median
  render start to visible not worse than +5%.
- Every close at most 3 s; median added close time at most 500 ms.
- Peak RSS of a hit at most 10% above the miss.
- Disk usage never above the 1 GiB budget.
- Compression: adopt only if at least 30% smaller and the disk hit is not
  slower.

## Restart results

Medians of five measured pairs (ranges for the render interval and RSS),
in milliseconds unless stated.

| Fixture | Condition | Render start to visible | Show to visible | Process to visible | Close | Peak RSS, MiB | Base tasks | AA tested / samples |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| standard 1696x1188 | off | 568.7 (548.6–596.6) | 771.6 | 1826.4 | 49.8 | 461 (445–485) | 1,007 | 2,014,848 / 1,568,864 |
| | miss | 584.3 (517.2–628.5) | 777.7 | 1811.6 | 112.0 | 465 (436–479) | 1,007 | 2,014,848 / 1,568,864 |
| | hit | 207.0 (191.7–213.7) | 408.7 | 1447.8 | 91.1 | 368 (362–374) | 0 | 0 / 0 |
| standard 3024x1964 | off | 1050.9 (1024.9–1134.3) | 1242.8 | 2316.6 | 42.2 | 1070 (733–1382) | 2,945 | 5,939,136 / 3,522,368 |
| | miss | 1033.5 (1012.3–1070.6) | 1216.3 | 2257.4 | 124.3 | 1384 (715–1400) | 2,945 | 5,939,136 / 3,522,368 |
| | hit | 314.3 (307.7–340.0) | 511.2 | 1555.3 | 122.6 | 611 (607–631) | 0 | 0 / 0 |
| deep 1696x1188 | off | 416.8 (386.3–491.7) | 609.2 | 1668.4 | 46.3 | 935 (815–951) | 11 | 0 / 0 |
| | miss | 461.3 (425.3–506.3) | 655.9 | 1760.3 | 69.7 | 864 (699–1027) | 11 | 0 / 0 |
| | hit | 84.2 (82.7–90.7) | 284.0 | 1332.2 | 43.0 | 319 (314–328) | 0 | 0 / 0 |
| deep 3024x1964 | off | 913.6 (779.5–933.7) | 1103.5 | 2138.5 | 51.6 | 1315 (1248–1434) | 11 | 0 / 0 |
| | miss | 918.8 (847.5–963.2) | 1098.1 | 2125.0 | 87.3 | 1395 (1231–1517) | 11 | 0 / 0 |
| | hit | 131.0 (129.8–132.5) | 327.9 | 1367.5 | 54.0 | 520 (493–524) | 0 | 0 / 0 |

Hit + palette launches matched the hit launches (render start to visible
209.1, 314.0, 85.3 and 133.1 ms by median).

| Fixture | Hit / miss render | Hit / miss show | Miss vs off render | Added close | Max close | Hit RSS vs miss |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| standard 1696x1188 | 35.4% | 52.6% | +2.7% | 62.2 | 116.4 | −20.9% |
| standard 3024x1964 | 30.4% | 42.0% | −1.7% | 82.1 | 129.3 | −55.9% |
| deep 1696x1188 | 18.3% | 43.3% | **+10.7%** | 23.4 | 77.6 | −63.1% |
| deep 3024x1964 | 14.3% | 29.9% | +0.6% | 35.7 | 90.0 | −62.7% |

All hit, close, RSS and disk limits pass. Show to visible would miss a 50%
limit for the standard 1696x1188 view (52.6%), because the window setup
before the render request is the same in both conditions; the limit applies
to the render interval, which was fixed before the campaign. The miss
overhead failed its +5% screening limit for deep 1696x1188 (overlapping
ranges, n = 5); see the confirmatory run below. The standard miss adds
62–82 ms to close: the frame completed less than the 1 s settle delay before
close, so close wrote it (and its AA) before returning, the worst case for a
short session.

### Exactness and work

In all 24 pairs (6 per fixture, warmup included) the off, miss, hit and
hit + palette launches produced identical sample and startup ARGB
fingerprints, and the Fire palette after a hit produced the same ARGB as the
Fire palette without the store. Every hit and hit + palette launch submitted
no base worker task and tested no AA pixel, and the palette change after a
hit took no AA candidate test or supersample for the standard and deep
fixtures (the standard palette changes took 18.9–26.1 ms after a hit against
28.0–40.8 ms without the store). Every miss computed its base frame.

The extra pairs were exact as well:

| Extra | Miss render | Hit render | Hit work | Palette after hit |
| --- | ---: | ---: | --- | --- |
| deep Julia 1696x1188, Refined | 3882.3 | 155.5 | none | 269.5 ms, 2,014,848 pixels tested, 0 samples |
| standard 1696x1188, Wide Preview | 567.9 | 211.6 | none | 20.2 ms, no AA work |
| standard 3024x1964, Wide Preview | 1054.0 | 311.3 | none | 28.7 ms, no AA work |

**Zero-candidate limitation (found, then fixed).** The deep Julia frame has
no AA candidates (2,014,848 pixels tested, no supersample). On `d5123d4` its
stored AA held only colors: AA samples were retained only when at least one
pixel was supersampled, both in memory and on disk. Within one launch the
palette change took 22.7 ms, consistent with no new AA pass, but after a
restart the first palette change tested every pixel again (269.5 ms at
1696x1188, the row above). The result was exact; the cost was candidate
detection only.

The fix keeps an empty sample set with its color scale and tested threshold
when a finished pass found no candidate, in memory and in the `.aa` file
(`REFINEMENT_FORMAT_VERSION` 2 adds a samples flag to its header). Colors
restored without samples still vouch for nothing beyond their own palette.
Remeasured on the fixed working tree (same machine and probe, 2026-11-16,
three off/miss/hit/hit + palette groups of the deep Julia extra):

| Launch | Render start to visible, ms | Palette change, ms | Palette AA work |
| --- | ---: | ---: | --- |
| store disabled (off) | 4127.9–4735.7 | 23.0–24.5 | none (no new pass) |
| hit, then palette | 326.9–337.9 | 20.5–21.5 | none: 0 pixels tested, 0 samples |

Every group produced the same sample, startup ARGB and Fire ARGB
fingerprints as before the fix, the hits did no base or AA work, and the
`.aa` file stayed the same size within 20 bytes (10,075,260 bytes). The
off and miss render times show the same bimodal deep Julia timing as the
confirmatory rerun below; the earlier measurements above are not rerun.

### Confirmatory miss overhead

No store work runs during a cold-miss render: the load is a missing-file
check and the write starts 1 s after completion. Before rerunning, the
confirmatory rule was declared: 12 alternating store-disabled and
store-enabled cold-miss pairs each for deep 1696x1188 and deep Julia
1696x1188 against the same +5% median limit, with the screening result
reported either way and a failure treated as a failed limit.

| Fixture | Off median (range) | Miss median (range) | Miss vs off | Median paired ratio |
| --- | ---: | ---: | ---: | ---: |
| deep 1696x1188 | 402.8 (369.4–486.2) | 402.8 (365.8–453.2) | +0.0% | 0.987 |
| deep Julia 1696x1188 | 4101.6 (2032.7–4877.9) | 2583.9 (2042.9–4659.3) | −37.0% | 0.790 |

Both pass. The deep Julia render time is bimodal in both conditions (about 2.0
or 4–4.9 s), which also explains the single extra pair (3.9 s miss); its
difference is not a store effect. Each fixture's 24 launches produced one
sample and ARGB fingerprint.

## Warm memory hits on the same scenarios

The restart measurements above start every launch in a new JVM, so they
cannot show a warm memory hit. A second campaign on the same day compared
memory hits, disk hits and cold misses as returns to the same views within
one launch. It ran on the working tree with the zero-candidate fix (base
`d5123d4`), on the same machine, display state and sleep inhibition as above.

**Method.** The restart fixtures, standard and deep at 1696x1188 and
3024x1964, opened as in the restart runs. After startup completed and
1.5 s for the store's settle delay, each launch ran 4 rounds. Each round
switched to Julia and back to Mandelbrot twice:

- a **memory** return with the frame cache untouched;
- a **cleared** return, with the in-memory frame cache cleared on the FX
  thread just before the return command.

The order of the two alternated by round. The latency runs from the return
command to the first post-layout pulse that shows the completed returned
scene, as in the Stage A validation. With the store disabled (**off**), a
cleared return is a cold miss; with the store enabled (**on**, empty profile
at launch), it is a disk hit. Each fixture ran 6 off/on launch pairs in
alternating order. Pair 0 was a warmup, excluded from timing but included in
the exactness checks, which leaves 20 returns of each kind per condition.

**Predeclared limits** (recorded before the first measurement):

1. Every return shows the launch's startup sample and ARGB fingerprints.
2. A memory return that finds its frame in the frame cache submits no base
   worker task and no AA supersample, in both conditions.
3. A disk-hit return submits no base worker task and no AA supersample.
4. The store-enabled memory-hit median is at most the store-disabled
   median × 1.05, or + 5 ms when that is larger.
5. The disk-hit median is at most 50% of the cold-miss median.

Memory-to-disk ratios, AA tested pixels, peak RSS and returns that missed
memory were reported without a limit.

Median (range) in milliseconds, 20 returns each:

| Fixture | Memory hit, off | Memory hit, on | Disk hit | Cold miss | Disk / cold | Memory (on) / disk |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| standard 1696x1188 | 41.7 (40.9–82.9) | 41.8 (41.4–83.0) | 74.7 (58.2–109.1) | 266.4 (258.0–316.7) | 28.0% | 56.0% |
| standard 3024x1964 | 74.8 (66.7–116.0) | 75.3 (66.6–241.0) | 158.2 (125.1–199.9) | 716.6 (683.1–791.3) | 22.1% | 47.6% |
| deep 1696x1188 | 41.6 (33.4–58.1) | 41.6 (33.1–58.2) | 33.4 (33.0–58.9) | 216.6 (199.8–458.4) | 15.4% | 124.6% |
| deep 3024x1964 | 62.9 (57.9–82.9) | 58.5 (58.0–99.5) | 58.3 (49.5–91.6) | 566.6 (524.8–883.3) | 10.3% | 100.3% |

All limits pass:

- **Exactness:** all 384 returns, warmup included, matched their launch's
  startup fingerprints.
- **Memory hits:** every memory return found its frame in the cache, at
  3024x1964 as well, and did no base or AA work.
- **Disk hits:** no base or AA work.
- **Cold misses:** they recalculated the base frame (11 worker tasks deep,
  1007 and 2945 standard). The standard ones also ran a full AA pass
  (2,014,848 and 5,939,136 pixels tested; 1,568,864 and 3,522,368
  supersamples).
- **Store cost on memory hits:** between −7.0% and +0.7%.
- **Disk hits:** 10.3–28.0% of the cold miss.

The latencies fall on display refresh steps (about 8.3 ms on this
120 Hz panel), so differences of one step, such as the deep memory and disk
hits, are not resolved. A standard disk hit costs roughly one to two more
steps than a memory hit, for reading the base frame and AA files. Closes
took at most 141.9 ms. Peak RSS per launch covers both return kinds and
the Julia frames: 959–1903 MiB deep and 991–2569 MiB standard (median of
the five measured launches per condition). It is not attributed to either
kind of hit.

## Disk sizes, compression and read latency

A private headless driver rendered realistic frames through the production
CPU backends into a temporary store, then measured the `.frame` file,
Deflate (`java.util.zip`, levels 1 and 6) of the whole file and
`PersistentFrameStore.load` on a fresh store (seven reads each, warm page
cache; the first read was compared bit for bit with the source). Times in
milliseconds.

| Fixture | Size | Frame file, MB | Deflate 1 | Deflate 1 compress / inflate | Deflate 6 | Load median (range) | Calculation |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Mandelbrot home | 1696x1188 | 26.2 | 48.7% | 254 / 59 | 48.0% | 7.4 (6.3–16.0) | 151 |
| Seahorse, cross orbit trap | 1696x1188 | 42.3 | 57.0% | 462 / 109 | 56.8% | 9.5 (9.4–10.0) | 159 |
| Deep BLA (`1e-30`, 2700 it.) | 1696x1188 | 26.2 | 0.5% | 16 / 12 | 0.1% | 6.1 (5.9–7.2) | 585 |
| Deep Julia (`1e-16`) | 1696x1188 | 26.2 | 6.1% | 34 / 19 | 1.7% | 6.1 (6.0–6.8) | 2,523 |
| Mandelbrot home | 3024x1964 | 77.2 | 49.1% | 769 / 174 | 48.4% | 18.3 (17.8–32.7) | 185 |
| Julia home | 3024x1964 | 77.2 | 59.3% | 933 / 200 | 58.4% | 23.9 (17.6–34.1) | 196 |
| Seahorse, cross orbit trap | 3024x1964 | 124.7 | 55.6% | 1,331 / 317 | 55.8% | 35.8 (34.1–38.6) | 419 |
| Deep boundary (`(0, 1)`, `1e-30`) | 3024x1964 | 77.2 | 50.5% | 737 / 186 | 52.9% | 21.7 (16.7–67.2) | 1,349 |
| Deep BLA | 3024x1964 | 77.2 | 0.5% | 46 / 35 | 0.1% | 32.2 (17.1–46.6) | 803 |
| Deep Julia | 3024x1964 | 77.2 | 4.0% | 77 / 49 | 1.0% | 22.2 (18.6–24.2) | 3,865 |

Compressed sizes are percentages of the frame file. Burning Ship (37.1 MB
at Deflate 1 of 77.2 MB) and the 1696x1188 Julia, Burning Ship, seahorse and
deep boundary frames followed the same pattern.

**Compression rejected.** Deflate halves typical frames (and reduces
interior-heavy deep frames to a few percent), but inflating a typical frame
takes 59–317 ms, 8–10 times the uncompressed read and between a third of
and about the whole time of recalculating the standard views. A compressed disk hit would be slower, so
the predeclared rule rejects it. Interior-heavy deep frames compress well and
inflate quickly; selective compression for them would save disk space but
not latency and was not pursued.

**Measured sizes and budget.** The stored files match the arithmetic
estimate (25.0 MiB and 73.6 MiB frames; 118.9 MiB at 3024x1964 for an
orbit-trap frame). AA files measured in the restart campaign:

| View | Frame | AA (colors and samples) | Entry |
| --- | ---: | ---: | ---: |
| standard 1696x1188, Refined | 26.2 MB | 14.6 MB | 40.8 MB |
| standard 3024x1964, Refined | 77.2 MB | 39.8 MB | 117.0 MB |
| deep Julia 1696x1188, Refined (colors only) | 26.2 MB | 10.1 MB | 36.3 MB |
| deep 1696x1188 / 3024x1964, Base only | 26.2 / 77.2 MB | none | 26.2 / 77.2 MB |

The 1 GiB budget therefore keeps about nine full-window 3024x1964 views
with AA, or about 26 such views at 1696x1188. It is retained: a disk hit
reads in tens of milliseconds, entries are evicted least recently used, and
the in-memory caches budget loading separately (the frame is read into the
frame being rendered, AA samples are bounded by the 512 MiB AA sample
cache). Peak RSS on a hit was 21–63% below the miss, because a restored
frame needs no worker or AA buffers.

## Correctness coverage

Portable unit tests (`PersistentFrameStoreTest`,
`PersistentRefinementStoreTest`) cover bit-identical restore of every
preset, Julia orbit-trap distances, deep Mandelbrot and deep Julia frames on
their exact decimal grids, a deep view restored from scene JSON, misses for
every key input (iteration limit, viewport, size, formula, orbit trap, Julia
parameters, sample accuracy), shifted (pan-reused) grids, partially reused
frames, cancellation during reads, settle-delay coalescing, LRU eviction
with AA kept or dropped as one entry, oversized entries, corrupt, truncated,
foreign and outdated (format or calculation version) entries, invalid AA
values behind a valid checksum, unavailable and read-only storage, close
during a write and stale temporary files. `PersistentAntialiasingFxTest`
restarts a controller over a store in JavaFX: stored AA restores with no AA
work in Refined and Fast, Base only restarts reveal stored AA later, and
palette changes after a return reuse the stored samples. Existing Stage A
tests cover pan in every direction, zero shift, no overlap, resize,
iteration changes and rapid switching within a launch; restarted frames
enter the same reuse paths only as complete frames.

## Not covered

- A manual restart walkthrough of the installed or development application
  (Dock launch, native menus) was not performed in these runs; the later
  independent verification ran the manual restart scenario on macOS.
- Windows and Linux desktop runs, other Macs, cold page cache reads and
  slower storage.
- External displays and display scale (DPI) changes between launches. A
  size change misses, as tested, but a frame stored at one scale and opened
  at another was not checked; it is an open ROADMAP P2.7 item.
