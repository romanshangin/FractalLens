# Qodana acceptance baseline

Baseline: 51 reviewed findings, source `90ee4bef187f985980c87f2f74dfc52468fa3039`,
Qodana Community 2026.2.3 (QDJVM-262.11550). Sanitized derivative retains relative
locations/fingerprints, omits machine IDs, absolute paths, invocation metadata/logs.

Accepted contracts: 33 owner/stack-managed resources, five supported JavaFX
reflection accesses, four Optional APIs, three bounded polls, three record
validation assignments, two exact divisions by two, one short-write loop.
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
