# P1.3 JavaFX publication experiment

> Historical evidence: results and “current source” below refer to the dated
> experiment, not a new validation of this checkout. Only the
> [selected evidence](README.md) is included; entries marked “private archive”
> are unavailable in this repository. Original SHA values and recorded commands
> describe the original run and may require private revisions or inputs. See
> [provenance and replay limits](../docs/development/PUBLICATION.md).

Date: 2026-09-24. Decision: **reject** the queued-progress coalescing candidate.
The experiment completes P1.3's measurement scope; it makes no production
publication change. The broader 9.3 improvement and 9.9 promotion gates remain
open.

## Scope and code-path accounting

`FractalRenderService.ProgressBatcher` schedules the first progress flush
immediately and later flushes at 16 ms. Each flush enqueues a callback; the FX
thread colors every region in a batch and calls `PixelBuffer.updateBuffer` once
with the bounding rectangle. AA tiles update their exact rectangle. Clearing,
full ready-pixel recoloring and palette publication request a full update.
Bounding rectangles can contain unchanged pixels when regions are separated.

`FractalSurface` swaps displayed and staging `SurfaceBuffer` instances after a
completed frame. It reuses a staging buffer when dimensions match, but allocates
a new one after a size change or when a retained progress image still owns the
old buffer. A 1512×982 heap ARGB backing array is 5,939,136 bytes; two such
arrays occupy 11,878,272 bytes before JavaFX image and native texture costs.
The retained-progress path can temporarily own a third array.

Tile AA copies each row of worker-owned colors into the PixelBuffer backing
array. Palette publication copies a complete color array once. Shift reuse
copies overlapping rows, and full ready-pixel recoloring can revisit colors
already published by progress. These copies have different ownership and
atomicity requirements; the measured copy cost alone does not prove that any
one can be removed safely.

## Visible JavaFX software-boundary probe

[`PublicationPathProbe`](../src/test/java/com/shangin/fractal/ui/PublicationPathProbe.java)
ran in a visible window on the native ES2 Prism pipeline at 1512×982 physical
buffer pixels. It used 20 warmup cycles, 80 measured cycles per variant and
three fresh JVM starts. The five variants rotate order each cycle. `DIRTY`
marks a 128×128 rectangle (1.10% of the frame); `FULL` changes the same pixels
but marks the whole buffer. `COPY_FULL` copies 5,939,136 bytes into the backing
array and marks the whole buffer. `REUSE_CLEAR` clears and updates an existing
buffer; `NEW_BUFFER` constructs a new PixelBuffer-backed surface. The image is
attached to a visible `ImageView` throughout. Raw samples are
[run 1](p1-3-publication-20260924/probe-1.csv),
[run 2](p1-3-publication-20260924/probe-2.csv) and
[run 3](p1-3-publication-20260924/probe-3.csv).

Median operation time in ms, one value per JVM start:

| Variant | Run 1 | Run 2 | Run 3 |
| --- | ---: | ---: | ---: |
| Dirty rectangle | 0.061 | 0.076 | 0.069 |
| Full update | 0.054 | 0.059 | 0.061 |
| Full copy and update | 0.463 | 0.504 | 0.382 |
| Reuse and clear | 0.097 | 0.103 | 0.112 |
| New buffer | 0.225 | 0.250 | 0.207 |

The following `AnimationTimer` intervals had medians of about 8.33 ms for all
variants on this 120 Hz session. `PixelBuffer.updateBuffer` can defer rendering,
so the operation times do not measure texture upload or physical scanout. The
probe does not run a full fractal request and cannot by itself justify changing
the dirty-rectangle, copy or staging policy. It establishes their local costs
and shows no visible-pulse timing separation under this workload.

## End-to-end coalescing candidate

The candidate combined progress regions from later scheduled flushes into one
already queued FX callback, preserving the first immediate flush and draining
pending regions before success. It had a deterministic queued-executor
regression and passed `FractalRenderServiceProgressTest` and
`FractalRenderServiceTest`. The unchanged control is Git revision
`03b2f105ea6141d06668f232c1456c74eb127c37`; the candidate was frozen from
that revision plus working-tree source hash `3ad109bd79426af6`. The build
identities and file hashes are recorded in the
[control](p1-3-publication-20260924/coalescing-pairs/build-A.json) and
[candidate](p1-3-publication-20260924/coalescing-pairs/build-B.json) manifests.
The candidate source differences remain in the private archive as
service (private archive: `p1-3-publication-20260924/source-snapshots/FractalRenderService.java.txt`)
and test (private archive: `p1-3-publication-20260924/source-snapshots/FractalRenderServiceProgressTest.java.txt`)
snapshots. Compiled build directories remain local to the measuring machine;
this repository keeps build hashes and the aggregate analysis. Source
differences and raw campaign results remain in the private archive.
The candidate was removed after the timing decision.

The [predeclared policy](policies/9-3-publication-coalescing-fx.json) used
1512×982 Fast-mode Mandelbrot overview, Julia AA, pan and cancellation cases.
Thirty alternating AB/BA process pairs had one measured sequence, three
warmups and a cold sequence per build. The runner checked fixture equality,
build immutability, complete rows and output fingerprints; its JavaFX driver
also compared final samples and every ARGB pixel against CPU controls. All 60
processes completed and the campaign has `CAMPAIGN_COMPLETE`. The machine was
an Apple M3 Pro with 18 GiB RAM, macOS 27.0, Homebrew OpenJDK 26.0.2 and
JavaFX 26.0.2. Observed power was AC and thermal pressure nominal before and
after every process; conditions within each process were not measured.

Selected results in ms, with matched candidate/control ratio and 95% bootstrap
interval (30 process pairs):

| Metric | Control median | Candidate median | Ratio interval | Gate |
| --- | ---: | ---: | ---: | --- |
| Overview full publication | 20.623 | 19.936 | 0.910–1.005 | Fail 10% target |
| Overview FX callback work | 5.994 | 5.888 | 0.964–0.995 | Fail 10% target |
| Overview first publication | 3.110 | 3.143 | 0.969–1.093 | Inconclusive 5% control |
| Julia AA full publication | 247.186 | 246.867 | 0.978–1.016 | Pass control |
| Pan full publication | 19.060 | 16.456 | 0.842–1.032 | Pass control |
| Cancellation tail | 0.592 | 0.694 | 0.943–1.365 | Inconclusive control |

The [complete analysis](p1-3-publication-20260924/coalescing-pairs/analysis.json),
matched pairs (private archive: `p1-3-publication-20260924/coalescing-pairs/pairs.csv`),
[environment](p1-3-publication-20260924/coalescing-pairs/environment.json) and
privately archived per-process rows/logs describe the original campaign.
The public analysis and environment record do not form a complete replay
bundle. The aggregate verdict is
`fail`: both declared targets failed, and several controls were inconclusive.
No retained improvement or production promotion is claimed. The software
publication boundary excludes OS input delivery, compositor completion and
physical scanout.

## Reproduction and limits

`prepare_baseline_build.py --working-tree` now freezes tracked source files and
labels the build with a source hash. It was used only for the candidate; the
control used the ordinary committed-revision path. The same JDK executable
built and ran both. The working-tree mode does not include untracked files,
so benchmark source files must be tracked before using them as inputs. The
saved build manifests include the exact source and compiled-file hashes.
The committed-revision path includes the macOS menu build script only when it
exists at that revision. Rebuilding historical `1a33e6f` and `9778ac9`
produced identities `03204a7d9dbcbbf4754756c0e4746ce171404d328e05499435602bdc43352a75`
and `c8dff651c71ca7ef1220df412cdd03f7834063ce2f4c3aeb96db27854331334f`,
matching the [P0.3 evidence](VALIDITY_MASK_OPTIMIZATION.md). A current
`03b2f10` build included the script and completed too.

The failed sandbox JavaFX attempt reached `Screen.getMainScreen` with no screens
and was interrupted. The unchanged `BaselineFxBenchmarkTest` passed in the
active graphical session. On the final tree, `mvn test` passed 447 tests with
54 opt-in skips; the graphical `BaselineFxBenchmarkTest` passed 8/8, and
`FractalResizeFxTest` passed 23/25 with two separately gated skips. The paired
summarizer's 16 Python tests passed. The visible probe and paired campaign ran
in the graphical session too.
No Windows/Linux FX run, extended navigation soak, memory leak study, physical
display capture or 9.9 promotion check was performed because the candidate was
rejected.
