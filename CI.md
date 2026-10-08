# Continuous integration

Historical checks belong to the original private repository. They do not certify
runs in the new destination; see [publication provenance](PUBLICATION.md).
The workflows use GitHub-hosted runners, a read-only `contents` token, immutable
action pins and checkouts with credential persistence disabled. No workflow uses
`pull_request_target`, repository secrets, write permissions or a personal runner.

## Portable build

`ci.yml` runs CPU-default tests on Ubuntu for PRs targeting `main` and pushes to
`feature/**`, `fix/**` and `chore/**`. `portable-ci.yml` runs macOS/Windows tests
on PRs, pushes to `main` and manual dispatch. Temurin Java 25, the Maven Wrapper
and the existing Maven cache are used. The macOS/Windows command is:

```sh
./mvnw --batch-mode --no-transfer-progress -Dfractal.gpu.enabled=false clean test
```

Native GPU, graphical JavaFX and benchmark profiles remain opt-in. A hosted
portable pass is not device conformance or a performance result.

## Console output and artifacts

Test and packaging commands run through `scripts/run-ci-tests` or its PowerShell
counterpart. Python 3 is required. `scripts/privacy.py` redacts known home,
project and temporary roots, the current login/hostname, email addresses other
than the approved contact, machine identifiers, addresses and common credential
forms before output reaches the console or `target/ci-test.log`. Known secret
environment values are also removed. Filtering failure withholds command output
and fails the step. Redaction is best effort, not proof of absence of secrets.

The test command's exit status is preserved after successful filtering. Failed
output keeps both diagnostic edges within the existing 8,000-unit console budget
(bytes in Bash; .NET characters in PowerShell). Successful output is also
filtered. The raw temporary capture is deleted; the local retained log is the
filtered version. Surefire reports and test logs are not uploaded automatically.

Runtime installers have a separate, explicit manual upload switch, off by
default. Generated provenance and smoke-report text must pass the privacy check
before upload. This does not inspect the contents of installers or establish
release acceptance; validate them in the private destination first. See
[packaging](RUNTIME_PACKAGING.md).

## Publication checks

`publication-checks.yml` runs on PRs, including forks, pushes to `main`,
`feature/**`, `fix/**` and `chore/**`, and manual dispatch. It needs no repository
secrets or write access. It runs the privacy regression tests, baseline analyzer
tests and these checks:

```sh
python3 scripts/check_public_data.py
python3 scripts/check_secrets.py --gitleaks /path/to/gitleaks
```

The public-data guard scans tracked files plus non-ignored new files, rejects
raw diagnostic/credential filenames, known prohibited identity patterns and
unreviewed benchmark paths. `scripts/public_benchmark_files.json` is the explicit
benchmark selection. Extending it requires reviewing the data and updating the
benchmark index. Known PNG/ICNS assets still require visual and metadata review;
this text guard does not inspect their pixels or prove binary privacy.

Gitleaks 8.30.1 is installed on Linux x64 from its official release after verifying
a pinned SHA-256. It scans the complete fetched Git history with default rules,
no project ignore list, inline allow comments disabled, archive/decode depth 5
and no size limit. The wrapper independently classifies only structured
benchmark JSON file-checksum matches. An arbitrary credential field is not a
checksum exception. Unresolved findings or scanner errors fail without printing
matched values. A full scan should also be run before a local commit; use
`--tree` to include current uncommitted files. Never publish the scanner report
without review.

Raw benchmark output remains private. The paired-run driver records OS,
release and architecture without querying a hostname. Benchmark metadata is
sanitized only when serialized; executable commands, locks and numerical
observations are unchanged. Generated directories and raw logs/dumps are ignored
by default. Existing selected evidence stays tracked. Local raw runs remain
sensitive and must not be force-added wholesale.

## Qodana and fork pull requests

The existing JVM Community linter runs without `QODANA_TOKEN` on every configured
PR, including forks, on `main` pushes and manual dispatch. The action is pinned
to v2026.2.2. It performs a full analysis (`pr-mode: false`) with comments,
annotations, fixes, cache export and raw report uploads disabled. This avoids write
operations that a fork's restricted token cannot perform and does not silently
skip fork analysis. The current linter version remains defined in `qodana.yaml`.

The analyzer image is pinned by digest. Its bootstrap compiles production and
test sources with the Maven Wrapper before a fresh import from `pom.xml`, using
the analyzer's dependency repository. Developer IDE metadata is moved into
private diagnostics in a disposable checkout. The image lacks ZIP utilities;
the bootstrap uses JDK ZIP support to extract the wrapper's checksum-verified
Maven distribution without changing its declared version or checksum.

The [reviewed baseline](.qodana/README.md) preserves 116 accepted source contracts
as sanitized locations and fingerprints. The zero-new-finding gate does not
claim a warning-free application. `scripts/check_qodana.py` also rejects missing
preparation evidence, unsuccessful analysis, unresolved dependency roots, sanity
findings, and new or changed findings. Baseline updates require source review.

After a successful run, only source-relative rule/location/message fields are
exported to `qodana-reviewed-findings`, retained for seven days. Both the
public-data guard and Gitleaks must pass before that upload. Raw SARIF, HTML,
logs, IDE metadata and caches are never uploaded. Local analysis does not prove
the changed workflow or actual fork permissions work on GitHub.

Community token behavior is documented by [JetBrains](https://www.jetbrains.com/help/qodana/github.html).
The runner/token restrictions follow [GitHub's security guidance](https://docs.github.com/en/actions/reference/security/secure-use)
and [fork event behavior](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows).

## Hardware validation

The former `native-gpu.yml` self-hosted workflow is removed from the current
configuration. Do not attach personal runners to the destination repository or
expose an organization runner group to it. Local hardware work remains manual
and its raw output private:

```sh
./mvnw -Pgpu-smoke test
```

Use an active desktop session on the actual target device, record only permitted
hardware/runtime fields and retain the platform limits from `ROADMAP.md`.
Windows CPU fallback checks are not Windows GPU conformance; Intel/AMD Mac and
Windows GPU promotion gates remain open. Removing a workflow does not unregister
runners from the old private repository, and this change does not alter that
repository's settings.

## Required private-destination acceptance

Local checks do not simulate GitHub's authorization or execute hosted workflows.
Before opening access, run a same-repository PR and a real external fork PR,
including the first-contributor approval flow. Confirm portable tests, publication
checks, Qodana and applicable packaging jobs run with read-only permissions and
no secrets. Confirm there are no PR comments, report uploads or runner requests
outside this policy. Inspect successful and failing Actions logs and any manually
uploaded installer/provenance files. Run on Windows and macOS; a PowerShell test
on macOS is not Windows acceptance. These destination checks remain pending
until the new private repository exists.
