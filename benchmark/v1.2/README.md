# V1.2 Benchmark Protocol

This directory is intentionally independent from `benchmark/tasks/v0.1`, its evaluator, and its frozen 5/14 result.

`manifest.json` freezes 10 DEV tasks and 20 TEST tasks before the first live provider run. Each task carries its initial fixture as a map of workspace-relative UTF-8 files; `V12FixtureWorkspace` materializes that map into a new task workspace and resets it for every run.

Evaluator assertions are never copied into the Agent workspace. The evaluator artifacts stored in this public repository are **runtime-hidden / protocol-held-out**, not a genuinely secret benchmark: a public-repository reader can inspect them. A private evaluator would require separate infrastructure and is deliberately out of scope for this protocol.

No live-model result exists yet. Do not tune prompts, router rules, tools, or strategy from TEST outcomes once this manifest is used for a formal run.

## Runner

The runner is safe by default: omitting `--provider` selects a deterministic no-action fake provider. It never invokes GLM implicitly.

```powershell
# One task, fake provider
mvn exec:java '-Dexec.mainClass=com.agent.benchmark.v12.V12BenchmarkMain' '-Dexec.args=--task=dev_01_auto_chat --provider=fake'

# DEV, fake provider
mvn exec:java '-Dexec.mainClass=com.agent.benchmark.v12.V12BenchmarkMain' '-Dexec.args=--split=dev --provider=fake'

# TEST requires an additional explicit gate even with fake provider
mvn exec:java '-Dexec.mainClass=com.agent.benchmark.v12.V12BenchmarkMain' '-Dexec.args=--split=test --provider=fake --confirm-test'

# Future real DEV example — do not run without explicit cost approval
mvn exec:java '-Dexec.mainClass=com.agent.benchmark.v12.V12BenchmarkMain' '-Dexec.args=--split=dev --provider=real --allow-real --max-provider-requests=65 --runtime-commit=<commit>'
```

Results are written below `benchmark-runs/v1.2/<run-id>/` as a summary, JSONL task records, sanitized trajectories, and a failure report. The request cap is enforced before delegating a provider call. TEST is never selected by default and requires `--confirm-test`.

## Current protocol blocker

The frozen recovery tasks require an Agent-visible Maven failure followed by a meaningful recovery and a final pass. Their current inline fixtures do not deterministically create such a failure, while hidden tests are intentionally unavailable until post-run evaluation. Consequently a trustworthy real baseline is **not ready** for those tasks: a first-pass success cannot be relabeled as recovery, and the runner does not manufacture a failure. This protocol issue must be resolved explicitly before a formal real DEV run.
