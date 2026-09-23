# Continuous integration

This document covers the portable CI and hardware validation lanes in the P0
infrastructure and delivery foundation of `ROADMAP.md`. Runtime artifact CI is
described in `RUNTIME_PACKAGING.md`. The Windows GPU runtime implementation and
Intel/AMD Mac acceptance work remain in roadmap 8.7 and 8.8.

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
provenance in the job log, and retains the full test log and Surefire reports for
30 days. The Apple Silicon and Intel Mac lanes run `./mvnw -Pgpu-smoke test` as
native conformance smoke tests. The Intel lane requires an x64 JVM on a physical
Intel Mac; its checkout step rejects an ARM64 host running an x64 process through
Rosetta. A passing smoke run is evidence for the selected device only, not for
all Intel or AMD GPUs, paired performance, or a packaged application.

Until the Windows runtime work in roadmap 8.7 is implemented, the Windows lane
requires the runtime to remain unavailable and verifies CPU fallback. It must
not be reported as Windows GPU conformance. This lane runs on Windows 10/11 x64
hardware while the Windows GPU runtime is unavailable; enabling a Windows GPU mode
requires a separate hardware-backed native lane after the runtime exists.

The runner labels are:

| Lane | Required labels |
| --- | --- |
| Apple Silicon macOS | `self-hosted`, `macOS`, `ARM64`, `fractalui-gpu` |
| Physical Intel Mac, x64 JVM | `self-hosted`, `macOS`, `X64`, `fractalui-gpu` |
| Windows x64 fallback | `self-hosted`, `Windows`, `X64`, `fractalui-gpu` |

A self-hosted machine must run GitHub Actions Runner 2.329.0 or newer because
the pinned actions use the current Node.js action runtime. A Windows runner
must also provide `gzip.exe`; the Maven cache uses it to create the archive at
the end of a job. The workflow accepts it on `PATH` or in the standard Git for
Windows directory `C:\Program Files\Git\usr\bin`, which it adds to subsequent
steps through `GITHUB_PATH`. Verify the installation with `Get-Command gzip.exe`
or `Test-Path 'C:\Program Files\Git\usr\bin\gzip.exe'`. The Windows job checks
this contract before Java and Maven cache setup.

A queued job means that no online runner matches all required labels. Before
accepting an Intel Mac result, retain the run URL, commit, selected-device
capability report, host model, GPU and driver details, Java/Maven versions, and
Surefire artifact. Run the packaged macOS x64 launcher on the same hardware and
record its CPU fallback and visible JavaFX behavior separately. Then complete
roadmap 8.8's missing-native, palette, calculation, paired timing, and transfer
gates. For Windows, complete 8.7's runtime, failure-path, palette, calculation,
and paired timing gates on actual Windows hardware before adding a GPU-conformance
lane or enabling a GPU mode there. A passing hosted portable job or build-machine
packaging smoke is not native GPU conformance. Neither hardware track blocks the
CPU-default macOS artifact; GPU promotion requires the correctness and
performance gates in `ROADMAP.md` on each supported target device.
