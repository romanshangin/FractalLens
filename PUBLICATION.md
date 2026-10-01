# Publication provenance and historical evidence

This project is prepared from a selected, sanitized history of a private
repository. The original repository and full migration records remain private.
Only selected project material belongs in the public repository; old branches,
PRs, Actions runs, artifacts and migration work directories are not part of it.

## Application name

The current application and repository are named FractalLens. Earlier commits
and structured measurement records retain the original application name as
historical provenance. Documentation uses the current name for readability;
that editorial change does not turn an earlier run into a FractalLens run.
Recorded numerical results, input hashes and original revision IDs are unchanged.

## Reading historical reports

Dates, decisions, measurements, test counts and platform limitations in existing
reports describe the original experiments. “Current source” in a dated report
means the source used at that time. Migration did not rerun those experiments,
promote rejected candidates, remove recorded failures or extend platform support.
Historical CI success is not evidence that a new destination's CI has passed.

The [benchmark index](benchmarks/README.md) identifies the evidence retained in
this checkout. A reference marked **private archive** identifies an original
artifact that is not distributed here. It is intentionally plain text rather
than a link to an inaccessible file. The path records its historical name; it
is not an instruction to look for that artifact in the current checkout.

The public selection retains decision reports, protocols, fixture definitions,
policies, compact numerical tables and selected final analyses. Raw logs,
per-process working records, source snapshots, diagnostic captures and most
exploratory datasets remain private. Some JSON summaries retain historical
input filenames, success-marker names and source/build references: these are
recorded provenance, not assertions that every referenced input is included.

## Redaction and identity

Selected environment evidence replaces the recorded hostname with `${HOST_NAME}`
and machine-specific temporary path roots with `${TEMP_DIR}`. Historical numeric
results and recorded source/build checksums are preserved. Redacted files are
derivatives and are not byte-identical original measurement artifacts.

The explicitly approved public identity consists of the surname `Shangin`, its
use in package/module names such as `com.shangin.fractal`, and the contact email
`shangin.ro@gmail.com`. These details may remain in author metadata and project
content. This permission does not cover login names, home paths, hostnames,
serial numbers, hardware UUIDs, MAC/IP addresses or machine-specific workspace
paths. See the publication rules in [AGENTS.md](AGENTS.md).

## Commit and artifact identifiers

History cleanup changes affected commit IDs and their descendants. The complete
old-to-new commit maps are retained privately. Existing experiment reports,
commit messages and measurement manifests preserve original revision identifiers;
an old SHA may therefore be absent from this history. A dirty experimental build
also cannot be reconstructed from its base commit alone.

Do not replace a measured revision or artifact checksum with a sanitized commit
ID and then claim that the experiment used the new object. Source/build hashes
identify the recorded input files; artifact hashes identify the original bytes.
Neither guarantees that a redacted artifact has the original checksum. Removed
signatures cannot authenticate rewritten commits; the original signatures remain
in the private archive. New documentation changes do not alter these historical
measurement identities.

## Reproduction limits and new runs

The selected evidence supports reading the historical conclusions, but it is
not a complete replay bundle. Analyses that require excluded samples, frozen
candidate sources, launch records or diagnostics cannot be recomputed solely
from this checkout. Original run commands in dated reports may require private
revisions and inputs; do not treat them as current copy-and-paste instructions.

For fresh measurements, follow the relevant current protocol, select an
available source revision, and use a new output directory. Record that revision,
source hashes, dependencies, workload, power/thermal conditions and exact-output
checks. Preserve the new raw evidence privately. Do not overwrite an existing
historical campaign or report new measurements as its continuation.

Before adding evidence to the public tree, inspect all selected files for secrets
and prohibited machine data. Keep raw logs and diagnostic dumps private. Publish
only necessary, reviewed, sanitized summaries or numerical evidence, and state
what is unavailable for independent replay. Secret scanning is a check, not a
proof that all conceivable private information has been found.

## Destination validation

Repository migration and historical acceptance are separate. Before public
access, the new destination must remain private while its selected refs, CI
results, logs and artifacts are checked. Fork-PR behavior and runner isolation
need their own validation. This document records provenance; it does not claim
those destination checks have passed.

## Prevention controls

The current [CI controls](CI.md) use hosted runners, read-only permissions,
secret-free Community analysis, a checked Qodana findings artifact, and explicit
installer upload. Local privacy guards and secret scanning complement review;
they do not certify uninspected
images, installers or future Actions logs. Destination fork-PR and platform
acceptance must still be completed before publication.
