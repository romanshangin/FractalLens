# Continuous integration

Historical private-repository runs do not certify the new destination:
[provenance](PUBLICATION.md). Workflows use GitHub-hosted runners, read-only
`contents`, immutable action pins and no persisted checkout credentials. No
`pull_request_target`, repository secrets, write permissions or personal runners.

## Portable build

`ci.yml` runs CPU-default tests on Ubuntu for PRs targeting `main` and pushes to
`feature/**`, `fix/**` and `chore/**`. `portable-ci.yml` runs macOS/Windows tests
on PRs, pushes to `main` and manual dispatch. Temurin Java 25, the Maven Wrapper
and the existing Maven cache are used. The macOS/Windows command is:

```sh
./mvnw --batch-mode --no-transfer-progress -Dfractal.gpu.enabled=false clean test
```

`ci.yml` also runs the task orchestrator tests on Ubuntu with Node.js 24 in a
separate `orchestrator` job.

Native GPU, graphical JavaFX and benchmark profiles remain opt-in. A hosted
portable pass is not device conformance or a performance result.

## Console output and artifacts

Test/packaging commands use `scripts/ci/run-ci-tests` or PowerShell equivalent
(Python 3 required). Before console/`target/ci-test.log`,
`scripts/publication/privacy.py` removes known home/project/temp roots, login/host,
nonapproved emails, machine IDs/addresses, common credentials and known secret
environment values. Filter failure withholds output/fails the step. Best-effort
redaction does not prove absence of secrets.

Successful filtering preserves command exit status and filters all output.
Failures retain both diagnostic edges within 8,000 units (Bash bytes, PowerShell
.NET characters). Delete raw temp capture; retain filtered local log only.
No automatic Surefire/test-log uploads.

Installer upload is separately/manual enabled, default off; provenance/smoke text
must pass privacy checks first. This neither inspects installer contents nor grants
release acceptance: validate privately. [Packaging](../packaging/RUNTIME_PACKAGING.md).

## Publication checks

`publication-checks.yml` runs on PRs, including forks, pushes to `main`,
`feature/**`, `fix/**` and `chore/**`, and manual dispatch. It needs no repository
secrets or write access. It runs the privacy regression tests, baseline analyzer
tests and these checks:

```sh
python3 scripts/publication/check_public_data.py
python3 scripts/publication/check_secrets.py --gitleaks /path/to/gitleaks
```

Public-data guard scans tracked/nonignored new files, rejecting raw diagnostic/
credential names, prohibited identity patterns and unreviewed benchmark paths.
Selection: `scripts/publication/public_benchmark_files.json`; additions need data
review/index update. PNG/ICNS still need visual/metadata review: text scanning
neither reads pixels nor proves binary privacy.

Install official Linux x64 Gitleaks 8.30.1 after pinned SHA-256 verification.
Scan complete fetched history with defaults, no project ignores/inline allow
comments, archive/decode depth 5, no size limit. Wrapper independently exempts
only structured benchmark-JSON file checksums, never arbitrary credential fields.
Findings/errors fail without matched values; report paths/rule IDs/lines/commits
grouped by top-level path/file, withholding paths rejected by public-data guard.
Run full scan before local commits too; separately use `--tree` for uncommitted
tracked/nonignored new files via temp copy. Ignored outputs/nested worktrees
like `.claude/worktrees/` are excluded. Review scanner reports before publication.

Keep raw benchmarks private. Pair driver records OS/release/architecture without
hostname queries. Sanitize metadata only at serialization; commands/locks/numbers
stay unchanged. Generated/raw logs/dumps default ignored, selected evidence stays
tracked. Never force-add sensitive local runs wholesale.

## Qodana and fork pull requests

JVM Community runs token-free (`QODANA_TOKEN` unused) for configured PRs/forks,
`main` pushes/manual dispatch. Action pin v2026.2.2; linter version:
`.qodana/qodana.yaml`. Full `pr-mode: false`, no comments/annotations/fixes/cache
exports/raw uploads: fork read-only tokens work without silently skipping analysis.

Digest-pinned image compiles production/tests via Maven Wrapper, then freshly
imports `pom.xml` with analyzer dependencies. Disposable checkout moves IDE metadata
to private diagnostics. Without ZIP utilities, JDK ZIP extracts checksum-verified
Wrapper Maven; declared version/checksum stay unchanged.

[Reviewed baseline](../../.qodana/README.md): 51 accepted contracts with sanitized
locations/fingerprints after module migration; zero-new is not zero-warning.
`scripts/quality/check_qodana.py` rejects missing preparation, failed analysis,
unresolved dependencies, sanity/new/changed findings. Baseline edits need source review.

Successful analysis exports source-relative rule/location/message only to
`qodana-reviewed-findings` for seven days, after public-data/Gitleaks passes.
Never upload raw SARIF/HTML/logs/IDE metadata/caches. Local analysis does not prove
GitHub workflow/fork permissions.

Community token behavior is documented by [JetBrains](https://www.jetbrains.com/help/qodana/github.html).
The runner/token restrictions follow [GitHub's security guidance](https://docs.github.com/en/actions/reference/security/secure-use)
and [fork event behavior](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows).

## Hardware validation

`native-gpu.yml` self-hosted workflow is removed. No personal runners/organization
runner groups on the destination. Hardware checks stay local/manual, raw output private:

```sh
./mvnw -Pgpu-smoke test
```

Use target-device active desktop; record only permitted hardware/runtime fields
and `ROADMAP.md` limits. Windows CPU fallback is not GPU conformance; Windows/
Intel/AMD Mac promotion remains open. Workflow removal neither unregisters old
private runners nor changes old repository settings.

## Required private-destination acceptance

Local checks cannot simulate GitHub authorization/run hosted workflows. Before
public access, test same-repository and real external-fork PRs, including first-
contributor approval. Confirm portable/publication/Qodana/applicable packaging
jobs, read-only permissions, no secrets or unauthorized comments/uploads/runner
requests. Inspect passing/failing Actions logs and manual installer/provenance
uploads. Run Windows/macOS; macOS PowerShell is not Windows acceptance. Pending
until the new private repository exists.
