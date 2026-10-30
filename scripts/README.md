# Repository scripts

Run from the repository root unless a tool specifies otherwise. Tools/tests by function:

| Directory | Responsibility | Entry points |
| --- | --- | --- |
| `orchestration/` | Task stages, candidate identity and reusable check evidence | `task-orchestrator.ts`, `check.ts` |
| `benchmarks/` | Frozen builds, measurement drivers, thermal probe, analyzers and plots | `prepare_baseline_build.py`, `run_baseline_pairs.py`, `run_frame_preparation.py`, `run_pipeline_latency.py`, `run_baseline_memory.py`, `summarize_*.py` |
| `ci/` | Buffered, privacy-filtered test output for Bash and PowerShell | `run-ci-tests`, `run-ci-tests.ps1` |
| `macos/` | Native menu bridge build and development launcher | `build-macos-menu`, `macos-java` |
| `packaging/` | Runtime installers, package contracts and application icon generation | `package_runtime.py`, `build_app_icon.py` |
| `publication/` | Redaction, public-data policy, secret scanning and benchmark allowlist | `privacy.py`, `check_public_data.py`, `check_secrets.py`, `install-gitleaks` |
| `quality/` | Qodana bootstrap and report acceptance | `prepare-qodana`, `check_qodana.py` |

Node metadata/TypeScript configuration: `orchestration/`. Install development types:

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

macOS launcher checks need macOS/native developer tools. Run
`scripts/ci/test-run-ci-tests.ps1` in PowerShell for wrapper checks. Desktop/hardware
acceptance follows `AGENTS.md`; analyzer tests do not execute campaigns.

[Orchestrator/check harness](../docs/development/TASK_ORCHESTRATOR.md);
[benchmark protocols/publication](../benchmarks/README.md). Historical commands
retain original paths/hashes. `prepare_baseline_build.py` selects current/legacy
native helpers by revision; moving tools neither changes snapshot identity nor
reruns measurements.
