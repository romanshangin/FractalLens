# Qodana acceptance baseline

The baseline contains 116 reviewed findings from source commit
`2859b0c5155252f35618c8d1abd73b5da3216a93`, analyzed with Qodana Community
2026.2.3 (QDJVM-262.11550). It is a sanitized derivative: source-relative
locations and matching fingerprints are retained; machine identifiers,
absolute paths, invocation metadata, and diagnostic logs are omitted.

The review classified 62 module-boundary findings, 33 owner/stack-managed
resource findings, seven explicitly supported JavaFX reflection accesses,
four Optional API contracts, three bounded benchmark polling loops, three
record-validation assignments, two exact divisions by two, one short-write
loop, and one branch-dominated boolean assignment. These are accepted
contracts, not a claim that all inspection warnings are false positives.

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
