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

`.github/workflows/portable-ci.yml` runs automatically for pull requests and
pushes to `main`, and can also be started manually. Its macOS and Windows jobs:

- start from a clean checkout and verify that it has no changes;
- install Temurin Java 25;
- cache Maven dependencies using `pom.xml` as part of the cache key;
- run `mvn --batch-mode --no-transfer-progress
  -Dfractal.gpu.enabled=false clean test`;
- retain `target/surefire-reports` for 30 days, including reports from failed
  test runs.

The portable jobs deliberately do not run the opt-in JavaFX, benchmark, or
native GPU profiles. They verify the CPU-default build without requiring a
display or Vulkan device. Third-party workflow actions are pinned to immutable
commit SHAs, with their release tags recorded beside each pin.

## Native GPU validation

`.github/workflows/native-gpu.yml` is manual-only. It routes the selected lane
to a self-hosted runner with real target hardware, records hardware and runtime
provenance in the job log, and retains Surefire reports for 30 days. The Apple
Silicon lane runs `mvn -Pgpu-smoke test` as a native conformance smoke test.
Until the Windows runtime work in roadmap 8.7 is implemented, the Windows lane
requires the runtime to remain unavailable and verifies exact CPU fallback; it
must not be reported as Windows GPU conformance.

The runner labels are:

| Lane | Required labels |
| --- | --- |
| Apple Silicon macOS | `self-hosted`, `macOS`, `ARM64`, `fractalui-gpu` |
| Windows x64 fallback | `self-hosted`, `Windows`, `X64`, `fractalui-gpu` |

A self-hosted machine must run GitHub Actions Runner 2.329.0 or newer because
the pinned actions use the current Node.js action runtime.

A queued job means that no online runner matches all required labels. A passing
hosted portable job must not be reported as native GPU conformance. GPU
promotion still requires the correctness and performance gates in
`ROADMAP.md` on the supported target hardware.
