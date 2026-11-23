# Qodana acceptance baseline

Baseline: 61 reviewed findings (51 from source `90ee4bef187f985980c87f2f74dfc52468fa3039`,
ten added for the Julia preview kernel, see below),
Qodana Community 2026.2.3 (QDJVM-262.11550). Sanitized derivative retains relative
locations/fingerprints, omits machine IDs, absolute paths, invocation metadata/logs.

Accepted contracts: 43 owner/stack-managed resources, five supported JavaFX
reflection accesses, four Optional APIs, three bounded polls, three record
validation assignments, two exact divisions by two (resolved in code, see
below), one short-write loop.
This does not classify all inspection warnings as false positives.

## Reviewed module-model migration

Original baseline: 116 findings at `2859b0c5155252f35618c8d1abd73b5da3216a93`.
Saved `ffeb29e62debc0ac3cf4bbf1b57afe6802a4fdc2` analysis found 51 original
fingerprints. Candidate found the same 51 with changed fingerprints after
`fractal-lens` became `fractal-lens.main` plus `fractal-lens.test`.

One-to-one migration matches candidate against saved main/original baseline by
rule/message/path/line/column. Physical offsets/snippets match; affected production
files are byte-identical across revisions/analyzed copies. Replace only these
reviewed fingerprints.

Remove 65 entries absent from both analyses: 62 `ClassEscapesItsScope`, two
`Java9ReflectionClassVisibility`, one `ConstantValue`. Absence proves no new fixes;
no new source contract was accepted.

Saved candidate analysis completed but exited 255: old baseline classified all
51 as new. `check_qodana.py` must continue rejecting that execution.

2026-10-29 host-terminal rerun used disposable candidate clone/migrated baseline
and workflow-pinned image. Tracked contents matched except IDE metadata/migration
docs; baseline/source/build/analysis configuration were byte-identical. Result:
51 `unchanged`, zero `new`, `executionSuccessful: true`, exit 0.
`scripts/quality/check_qodana.py` independently accepted results/reproduced 51-entry
export. Local migration proof only, not GitHub/platform execution.

## Reviewed Metal bootstrap migration

PR #47, `216a08a13de9973927515bf5eab2b00e92b5749e`: 48 unchanged plus three
new `BusyWait` fingerprints, the same bounded polls at 192/207/303 in
`InteractionLatencyBenchmark.java`. Bootstrap removed outer `Application` and added
nested lifecycle; `run`/`awaitIdle` are byte-identical to `main` at
`ba05a0ce5017c9de888fc1e5e1411fb0b6c95fa2`.

Pinned-image local analysis reproduced all three; match prior rule/message/path/
column, one-line shift and exact saved successful snippets. Update only those
locations/fingerprints; other 48 entries/contracts/profile/zero-new gate stay.
No new polling contract accepted. Reproduction exited 255, remaining invalid
execution evidence even after migration.

2026-10-29 fresh local disposable source clone, migrated baseline only, pinned
image/unchanged preparation: 51 unchanged, zero new, `executionSuccessful: true`,
exit 0. `scripts/quality/check_qodana.py` accepted all 51; nine acceptance tests
passed. This does not pass the failed GitHub invocation. Raw logs/reports stay private.

## Reviewed window-title migration (2026-11-08)

PR #62, `c4a787b7e441830229cc2ba7a6c0e2c9ef1eeacd`: 45 unchanged plus three new
`BusyWait` fingerprints, the same bounded polls at 192/207/303 in
`InteractionLatencyBenchmark.java`. The only edit in that file is the window-title
string at line 84; the polling code is unchanged.

Pinned-image local analysis on a disposable clone reproduced all three, matching
rule/message/path/line/column. Only those fingerprints were updated; other
entries/contracts/profile/zero-new gate stay. No new polling contract accepted.
The reproduction exited 255, remaining invalid execution evidence.

Fresh local disposable clone, migrated baseline only, pinned image/unchanged
preparation: 48 unchanged, zero new, exit 0. `scripts/quality/check_qodana.py`
accepted all 48; nine acceptance tests passed. This does not pass the failed GitHub
invocation. Raw logs/reports stay private.

## Resolved exact divisions (spare frame storage, 2026-11-07)

The two `BigDecimalMethodWithoutRoundingCalled` entries in `FrameReusePlanner`
(halving the integer resize difference) were reported as new after unrelated edits
in that file changed their fingerprints. Instead of migrating them, the divisions now
pass `MathContext.UNLIMITED`. With precision 0 the JDK performs the same exact
division, so the results are identical. The two baseline entries stay in the file
and become resolved (`absent`), which the gate allows; no new contract was accepted.

## Resolved short-write loop (wide preview settings, 2026-11-08)

The `IgnoreResultOfCall` entry for the `FileChannel.write` loop in
`LastSessionStore.atomicWrite` was reported as new after edits to that method changed
its fingerprint. Instead of migrating it, the loop now adds up the returned byte
counts. It still writes until every byte is written. The baseline entry stays in the
file and becomes resolved (`absent`), which the gate allows; no new contract was
accepted.

## Reviewed Julia preview kernel (PR #86, 2026-11-22)

PR #86, `7c90457d09d956c142ad251c7e9383ca41b180e2`, added the opt-in
`VulkanJuliaPreviewKernel`. Its first CI analysis found 47 unchanged plus 12 new:

- Ten `AutoCloseableResource` in `VulkanJuliaPreviewKernel.java` at lines
  76/83/91/138/139/210/220/221/265/342. They are the same owner/stack-managed pattern
  as the accepted entries of the other Vulkan kernels: `.get(i)` views of LWJGL
  structs allocated on a `MemoryStack` that the enclosing `try` frees, and the
  kernel's own `Buffer`, owned by its `buffers` array and closed in `close()`.
  Accepted as ten new instances of the existing contract (33 → 43); no new contract.
- One `AutoCloseableResource` at `VulkanComputeSession.java:98`: the accepted line-97
  entry (`queues.get(0)`) shifted by the new `juliaPreviewKernel` field, with the same
  rule/message/column and code. Its location and fingerprints were migrated.
- One `EmptyStatementBody` in `JuliaGpuRenderer` (the deferred-close wait loop) was
  fixed in code instead of accepted.

Pinned-image local analysis on a disposable clone of `7c90457` reproduced the same
47 + 12. With the code fix and the migrated baseline on a fresh disposable clone:
58 unchanged, zero new, `executionSuccessful: true`, exit 0 (the three entries
already resolved before this PR stay `absent`). `scripts/quality/check_qodana.py`
accepted all 58. Local evidence only, not the GitHub run; raw logs/reports stay
private.

## Reviewed Julia explorer latency and GPU bright-point pass (PR #88, 2026-11-23)

PR #88's first CI analysis, at `0eedd3a`, found 48 unchanged and 15 new findings.

- Four `BusyWait` findings were bounded polls in the new `JuliaExplorerLatencyBenchmark`.
  They were fixed in code, not accepted: the driver now waits on a future that an
  `AnimationTimer` completes when the condition holds on the FX thread.
- One `AutoCloseableResource` was a local optional `Buffer` for the bright-point output.
  It was fixed in code: the budget is checked before the `Buffer` is constructed, and
  the constructor is unchanged from `main`.
- Ten `AutoCloseableResource` findings in `VulkanJuliaPreviewKernel.java` are the
  accepted PR #86 entries. The second pipeline, the fourth descriptor and the budget
  check shifted their lines to 82/89/98/144/145/230/266/267/318/394. Rule, message,
  column and code match the saved entries (only `buffers[i]` became the equivalent
  local `buffer` at line 266). Their locations and fingerprints were migrated.
  No new contract was accepted, and the other entries are unchanged.

Pinned-image local analysis on disposable copies reproduced the findings. A copy with
both code fixes reported exactly the ten shifted entries. A fresh copy with the
migrated baseline gave 58 unchanged, zero new, exit 0, and `check_qodana.py` accepted
the result. This is local evidence only, not the GitHub run; raw logs and reports
stay private.

## Acceptance and report handling

CI freshly imports Maven without IDE metadata, compiles production/tests with
analyzer dependencies, rejects new/sanity findings, preparation failures and
unresolved dependencies. Resolved baseline findings are allowed. Never auto-refresh
baseline or accept unreviewed findings for green CI.

Run pinned image with `--baseline .qodana/baseline.sarif.json` on a disposable
`/data/project` copy only. Bootstrap moves IDE metadata/compilation diagnostics
to private results; never mount the working checkout.

Only allowlisted findings JSON may upload as seven-day artifact after personal-
data/secret checks. Raw SARIF/logs/caches/HTML stay private. Read-only GitHub token;
no cloud token/comments/annotations/fix pushes needed.
