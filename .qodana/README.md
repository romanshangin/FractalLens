# Qodana acceptance baseline

The baseline contains 51 reviewed findings from source commit
`90ee4bef187f985980c87f2f74dfc52468fa3039`, analyzed with Qodana Community
2026.2.3 (QDJVM-262.11550). It is a sanitized derivative: source-relative
locations and matching fingerprints are retained; machine identifiers,
absolute paths, invocation metadata, and diagnostic logs are omitted.

The review classified 33 owner/stack-managed resource findings,
five explicitly supported JavaFX reflection accesses,
four Optional API contracts, three bounded benchmark polling loops, three
record-validation assignments, two exact divisions by two, and one short-write
loop. These are accepted
contracts, not a claim that all inspection warnings are false positives.

## Reviewed module-model migration

The original baseline contained 116 findings from source commit
`2859b0c5155252f35618c8d1abd73b5da3216a93`. The saved analysis of
`ffeb29e62debc0ac3cf4bbf1b57afe6802a4fdc2` reported 51 of those findings,
all matching their original fingerprints. The candidate analysis reported
the same 51 findings, but the imported production module changed from
`fractal-lens` to `fractal-lens.main`, alongside a separate
`fractal-lens.test` module. All 51 fingerprints changed.

The migration matched each candidate finding one-to-one against both the
saved main analysis and the original baseline by rule, message, source path,
line, and column. Physical locations, including offsets and source snippets,
were identical between the two analyses, and every affected production file
was byte-identical between the source revisions and analyzed copies. Only
the fingerprints of these previously reviewed findings were replaced.

The 65 historical entries absent from both saved analyses were removed:
62 `ClassEscapesItsScope`, two `Java9ReflectionClassVisibility`, and one
`ConstantValue`. Their absence is not evidence of newly implemented fixes.
No newly reported source contract was accepted.

The saved candidate invocation completed analysis but exited with code 255
because all 51 findings were classified as new against the old baseline.
The saved failing invocation must continue to be rejected by
`check_qodana.py`.

On 2026-10-29, a fresh disposable clone of the candidate, with the migrated
baseline, was analyzed from the host terminal using the image digest pinned
in the Qodana workflow. The analyzed tracked files matched the candidate,
except for developer IDE metadata and migration documentation; the baseline,
source, build settings, and analysis configuration were byte-identical.
The fresh analysis reported 51 `unchanged` findings and zero `new` findings,
with `executionSuccessful: true` and exit code 0. The current
`scripts/quality/check_qodana.py` independently accepted the saved results
and reproduced the exported 51-finding report. This verifies the local
baseline migration, not execution of the GitHub workflow or other platforms.

## Reviewed Metal bootstrap migration

PR #47's source commit `216a08a13de9973927515bf5eab2b00e92b5749e`
reported 48 unchanged findings and three new `BusyWait` fingerprints. These
are the same three bounded polling loops already reviewed in this baseline,
at lines 192, 207 and 303 of `InteractionLatencyBenchmark.java`. The bootstrap
refactor removed the outer class's `Application` superclass and introduced a
nested lifecycle adapter; the containing `run` and `awaitIdle` methods are
byte-identical to `main` at `ba05a0ce5017c9de888fc1e5e1411fb0b6c95fa2`.

A local analysis with the pinned image reproduced all three new fingerprints.
Each was matched one-to-one to its prior baseline entry by rule, message,
source path, column and the one-line location shift. The surrounding source
snippets also matched the saved successful pre-refactor analysis exactly.
Only those three locations and fingerprint pairs were updated; the other
48 entries, accepted contracts, analysis profile and zero-new-finding gate
are unchanged. No additional polling contract is accepted by this migration.
The reproduced analysis exited with code 255 and remains invalid execution
evidence even with the updated baseline.

A fresh local analysis on 2026-10-29 used a disposable clone of that source
commit with only the migrated baseline substituted, the pinned image and
the unchanged preparation/configuration scripts. It reported 51 unchanged
findings, zero new findings, `executionSuccessful: true` and exit code 0.
`scripts/quality/check_qodana.py` independently accepted all 51 findings;
its nine report-acceptance tests passed. This is a local baseline validation,
not a claim that the failed GitHub invocation passed. Raw reports and logs
remain private.

## Acceptance and report handling

CI performs a fresh Maven import without developer IDE metadata, compiles
production and test sources with dependencies in the analyzer's repository,
and rejects new findings, failed preparation, unresolved dependency roots,
and sanity findings. Resolved baseline findings are allowed. Do not refresh
the baseline automatically or add unreviewed findings to obtain a green run.

Use a disposable source copy mounted at `/data/project` when running the
pinned image locally with `--baseline .qodana/baseline.sarif.json`. The bootstrap
moves IDE metadata into private results and writes compilation diagnostics
there. Never mount your working checkout for this preparation.

Only the allowlisted findings JSON is eligible for the seven-day CI artifact,
after personal-data and secret checks. Raw SARIF, logs, caches, and the HTML
report remain private. The GitHub token has read-only repository access;
analysis needs no cloud token, comments, annotations, or fix pushes.
