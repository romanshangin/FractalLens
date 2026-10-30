# P1.3 JavaFX publication experiment

> Historical evidence: results and “current source” describe the dated experiment, not validation of this checkout. Only [selected evidence](README.md) is included; “private archive” entries are unavailable here. Original SHAs/commands may require private revisions or inputs. See [provenance and replay limits](../docs/development/PUBLICATION.md).

Date: 2026-09-24. Decision: **reject** the queued-progress coalescing candidate.
The experiment completes P1.3's measurement scope; it makes no production
publication change. The broader 9.3 improvement and 9.9 promotion gates remain
open.

## Scope and code-path accounting

`FractalRenderService.ProgressBatcher` flushes first immediately, later at 16 ms.
Each callback colors batch regions on FX, then one bounding-rectangle
`PixelBuffer.updateBuffer`; gaps may include unchanged pixels. AA uses exact tiles;
clear/full ready recolor/palette request full updates.

`FractalSurface` swaps display/staging `SurfaceBuffer` on completion, reusing
same-size staging unless retained progress owns it. 1512×982 ARGB =5,939,136 bytes;
two =11,878,272 before FX/native textures; retained progress may temporarily add third.

AA copies worker rows; palette copies full array; reuse copies overlap rows;
ready recolor can revisit progress colors. Different ownership/atomicity means
copy timing alone cannot justify removal.

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

`AnimationTimer` medians ~8.33 ms across variants at 120 Hz. Deferred
`PixelBuffer.updateBuffer` excludes upload/scanout. Local probe, not full request:
no visible-pulse separation and no standalone justification to change dirty/copy/
staging policy.

## End-to-end coalescing candidate

Candidate coalesced later progress into queued FX callback, retaining immediate
first flush/draining before success. Deterministic queued test and
`FractalRenderServiceProgressTest`/`FractalRenderServiceTest` passed.
Control `03b2f105ea6141d06668f232c1456c74eb127c37`; candidate adds working-tree
hash `3ad109bd79426af6`. [A](p1-3-publication-20260924/coalescing-pairs/build-A.json)/
[B](p1-3-publication-20260924/coalescing-pairs/build-B.json) retain build/file hashes.
Private source/test:
`p1-3-publication-20260924/source-snapshots/FractalRenderService.java.txt`,
`p1-3-publication-20260924/source-snapshots/FractalRenderServiceProgressTest.java.txt`.
Compiled snapshots stay local; public tree retains hashes/aggregate analysis,
not raw campaigns/source diffs. Candidate removed after timing decision.

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

[Analysis](p1-3-publication-20260924/coalescing-pairs/analysis.json), private pairs
`p1-3-publication-20260924/coalescing-pairs/pairs.csv`,
[environment](p1-3-publication-20260924/coalescing-pairs/environment.json) and private
process CSV/logs describe original run, not complete public replay. `fail`: both
targets fail, several controls inconclusive. No retained gain/promotion; publication
excludes OS delivery/compositor/scanout.

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

Sandbox hit empty `Screen.getMainScreen` and was interrupted; unchanged
`BaselineFxBenchmarkTest` passed active desktop. Final `mvn test`: 447 tests,
54 opt-in skips; FX benchmark 8/8, `FractalResizeFxTest` 23/25 with two gated skips;
16 Python pair tests pass. Probe/campaign also ran on desktop. Rejected candidate
received no Windows/Linux FX, extended soak/leak study, screen capture or 9.9 promotion.
