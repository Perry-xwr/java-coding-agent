# Public Release Audit

## 1. Repository

- Local development repository: `<repo-root>`
- Public repository target: `https://github.com/Perry-xwr/java-coding-agent.git`
- The existing project directory remains the only development copy; no second repository directory was created.

## 2. Current Branch

- Branch: `resume-upgrade`
- Pre-release base commit: `9183be8 docs: improve project documentation and runtime experience`
- Existing history is preserved; no amend, reset, rebase, or force operation was used.

## 3. Remote Mapping

| Remote | URL | Purpose |
|---|---|---|
| `origin` | `https://github.com/Perry-xwr/java-coding-agent.git` | Primary public repository |
| `course` | `https://github.com/Perry-xwr/Java-agent-cli.git` | Preserved course repository |

This release must be pushed only to `origin`.

## 4. Documentation Created

- `README.md`: public project overview, safety model, tools, benchmark, experiments, held-out result, usage, limitations, and roadmap
- `docs/architecture.md`: runtime and evaluation architecture
- `docs/benchmark.md`: benchmark schema, isolation, evaluator, splits, and metrics
- `docs/experiments.md`: positive and negative strategy experiments
- `docs/failure-analysis.md`: held-out TEST failure themes
- `docs/v1-final-test-report.md`: frozen V1 TEST record
- `examples/demo.md`: successful `logic_004` workflow
- `SECURITY.md`: workspace, editing, process, testing, and credential policies

## 5. Root Cleanup

The repository root now contains only core project directories and public entry-point files: `src/`, `benchmark/`, `docs/`, `examples/`, `README.md`, `SECURITY.md`, `LICENSE`, `pom.xml`, and `.gitignore`. Local ignored directories/files (`target/`, `.m2/`, `.env`) remain present but are not publishable Git content.

## 6. Archived Files

The following development-stage records were moved to `docs/archive/`:

- Phase 1 through Phase 7 reports
- Benchmark quality calibration and evaluator quality audit
- Calibrated Diagnostic Recovery report
- REACT, Action-Oriented, Diagnostic Recovery, Planning, and Precise Edit real-DEV reports
- Original project audit

Negative experiments were preserved rather than deleted.

## 7. Secret Scan

- `.env` is ignored and not tracked.
- `target/`, `.m2/`, and `benchmark/results/` are ignored and not tracked.
- A repository scan checked `GLM_API_KEY`, Authorization, Bearer, `api_key`, password, secret, and token patterns.
- Keyword hits were limited to documentation, environment-variable names, redaction code, and explicit synthetic test values.
- A credential-value pattern scan returned no matches.
- No API key or other real credential was found in the publishable files.

Result: **PASS**.

## 8. Absolute Path Scan

README, SECURITY, docs, and examples were scanned for `D:\Development\` and `C:\Users\`. Historical report references were normalized to `<repo-root>`.

Result: **PASS — no personal absolute path remains in public documentation**.

## 9. License Audit

No third-party copyright header, SPDX marker, teacher-provided restricted template notice, or conflicting source license was found in `src/` or `benchmark/`. Runtime dependencies remain external Maven dependencies under their own licenses and are not copied into the repository.

An MIT license was added with copyright holder `Perry-xwr`.

Result: **PASS**.

## 10. mvn test

The host Maven configuration initially resolved its local repository to the non-writable path `C:\.m2\repository`, before tests started. The same test lifecycle was then run with the repository-local Maven cache:

```powershell
mvn "-Dmaven.repo.local=<repo-root>\.m2\repository" test
```

Result:

```text
Tests run: 121
Failures: 0
Errors: 0
Skipped: 5
BUILD SUCCESS
```

The skipped tests are explicit opt-in/platform-dependent integration cases. The ordinary suite did not call the real GLM provider.

## 11. Git Status

The release contains the accumulated V1 runtime, safety, trajectory, benchmark, tests, public documentation, and archive work on `resume-upgrade`. Review confirmed that no build output, Maven cache, benchmark result trajectory, IDE cache, secret file, or accidental binary is included.

The release will be committed as a new commit without altering earlier course history.

## 12. Ready To Publish

**READY**

The project is ready to commit as `Release Java Coding Agent V1` and push to the new public `origin`, subject to normal GitHub authentication and a non-conflicting remote branch history.
