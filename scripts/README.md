# Repository scripts

Run commands from the repository root unless a tool documents another working
directory. Tools and their tests are grouped by function:

| Directory | Responsibility | Entry points |
| --- | --- | --- |
| `orchestration/` | Task stages, candidate identity and reusable check evidence | `task-orchestrator.ts`, `check.ts` |
| `benchmarks/` | Frozen builds, measurement drivers, thermal probe, analyzers and plots | `prepare_baseline_build.py`, `run_baseline_pairs.py`, `run_pipeline_latency.py`, `run_baseline_memory.py`, `summarize_*.py` |
| `ci/` | Buffered, privacy-filtered test output for Bash and PowerShell | `run-ci-tests`, `run-ci-tests.ps1` |
| `macos/` | Native menu bridge build and development launcher | `build-macos-menu`, `macos-java` |
| `packaging/` | Runtime installers, package contracts and application icon generation | `package_runtime.py`, `build_app_icon.py` |
| `publication/` | Redaction, public-data policy, secret scanning and benchmark allowlist | `privacy.py`, `check_public_data.py`, `check_secrets.py`, `install-gitleaks` |
| `quality/` | Qodana bootstrap and report acceptance | `prepare-qodana`, `check_qodana.py` |

The Node package metadata and TypeScript configuration belong to `orchestration/`.
Install its development type definitions with:

```sh
npm ci --prefix scripts/orchestration
```

Common validation commands:

```sh
node --test scripts/orchestration/test_task_orchestrator.ts scripts/orchestration/test_validation_evidence.ts
python3 -m unittest discover -s scripts/benchmarks -p 'test_*.py'
python3 -m unittest discover -s scripts/publication -p 'test_*.py'
python3 -m unittest discover -s scripts/quality -p 'test_*.py'
python3 scripts/packaging/test_package_runtime.py
bash scripts/ci/test-run-ci-tests
python3 scripts/macos/test_macos_launcher.py
```

The macOS launcher checks require macOS and its native developer tools.
PowerShell wrapper checks run with `scripts/ci/test-run-ci-tests.ps1` in a
PowerShell session. JavaFX/desktop and hardware-backed benchmark acceptance
still follow `AGENTS.md`; analyzer tests do not execute measurement campaigns.

For the task workflow and check harness, see
[the orchestrator guide](../docs/development/TASK_ORCHESTRATOR.md).
For benchmark protocols and publication rules, see
[the benchmark index](../benchmarks/README.md).
Historical measurement records retain their original script paths and hashes.
`prepare_baseline_build.py` selects either the current or legacy native helper
path from the requested revision; moving current tooling does not rewrite old
snapshot identities or rerun historical measurements.
