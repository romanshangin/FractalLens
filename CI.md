# Continuous integration

This document covers only item 1 of the P0 infrastructure and delivery
foundation in `ROADMAP.md`: continuously reproducible CI. It does not complete
P0 or its exit criterion. Runtime packaging and clean-machine smoke tests,
scene serialization and restore, the open measurement decision, and the
remaining hardware validation work are separate open roadmap items.

FractalUI separates portable CPU-default validation from hardware-native GPU
validation. Hosted runners establish portability; they do not establish GPU
conformance or performance.

## Portable build

`.github/workflows/ci.yml` runs the CPU-default suite on Ubuntu for pull
requests targeting `main` and pushes to `codex/**` branches.

`.github/workflows/portable-ci.yml` runs automatically for pull requests and
pushes to `main`, and can also be started manually. Its macOS and Windows jobs:

- start from a clean checkout and verify that it has no changes;
- install Temurin Java 25;
- cache Maven dependencies using `pom.xml` as part of the cache key;
- run `./mvnw --batch-mode --no-transfer-progress
  -Dfractal.gpu.enabled=false clean test`;
- retain `target/surefire-reports` for 30 days, including reports from failed
  test runs.

The portable jobs deliberately do not run the opt-in JavaFX, benchmark, or
native GPU profiles. They verify the CPU-default build without requiring a
display or Vulkan device. The checked-in Maven Wrapper pins Maven 3.9.16 and
removes a preinstalled-Maven requirement from hosted and self-hosted runners.
Third-party workflow actions are pinned to immutable commit SHAs, with their
release tags recorded beside each pin.

All CI test commands run through `scripts/run-ci-tests` (or its PowerShell
equivalent). The complete Maven output is retained as `target/ci-test.log`. A
failed run prints at most 8,000 output units to the job console, including the
status line and truncation marker, while preserving both ends. The Bash runner
counts bytes; the PowerShell runner counts .NET characters. The uploaded log
and Surefire reports retain the complete diagnostics.

The hosted lanes run `scripts/test-run-ci-tests` and the Windows-specific
PowerShell counterpart before Maven. These contract checks use a deterministic
failing process to verify exit-code propagation, complete log retention, the
console limit, both retained edges and the reported omission size.

## Native GPU validation

`.github/workflows/native-gpu.yml` is manual-only. It routes the selected lane
to a self-hosted runner with real target hardware, records hardware and runtime
provenance in the job log, and retains Surefire reports for 30 days. The Apple
Silicon lane runs `./mvnw -Pgpu-smoke test` as a native conformance smoke test.
Until the Windows runtime work in roadmap 8.7 is implemented, the Windows lane
requires the runtime to remain unavailable and verifies exact CPU fallback; it
must not be reported as Windows GPU conformance.

The runner labels are:

| Lane | Required labels |
| --- | --- |
| Apple Silicon macOS | `self-hosted`, `macOS`, `ARM64`, `fractalui-gpu` |
| Windows x64 fallback | `self-hosted`, `Windows`, `X64`, `fractalui-gpu` |

A self-hosted machine must run GitHub Actions Runner 2.329.0 or newer because
the pinned actions use the current Node.js action runtime. A Windows runner
must also expose `gzip.exe` on the runner service account's `PATH`; the Maven
cache uses it to create the archive at the end of a job. Verify the requirement
from that account with `Get-Command gzip.exe` and `gzip.exe --version`, and
restart the runner after changing `PATH`. The Windows job checks this contract
before Java and Maven cache setup.

A queued job means that no online runner matches all required labels. A passing
hosted portable job must not be reported as native GPU conformance. GPU
promotion still requires the correctness and performance gates in
`ROADMAP.md` on the supported target hardware.
