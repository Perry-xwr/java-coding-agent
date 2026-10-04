# Offline Demos

These walkthroughs use deterministic scripted/fake model tests. They do not call GLM or another provider; they demonstrate runtime paths without implying that a live model will choose the same tools or succeed. The fixture folders are small examples of the workspace classes discussed below. The scripted tests use isolated temporary workspaces so they cannot edit the checked-in demo fixtures.

Run commands from the repository root. Java 17 and the dependencies already resolved for this project are required. These PowerShell examples use the repository-local ignored Maven cache; omit the `-Dmaven.repo.local=...` property if you prefer Maven's normal user cache.

## Demo 1 — Basic coding edit

Shows a scripted CODE Agent mutation followed by runtime reread, local `javac` verification, and completion:

```powershell
$repo = (Get-Location).Path
mvn "-Dmaven.repo.local=$repo\.m2\repository" "-Dtest=PostEditVerificationAgentTest#standaloneJavaUsesJavacVerifierInsteadOfMavenTool" test
```

The test uses a fake LLM and a temporary Java workspace; it asserts a syntax PASS and confirms that a standalone workspace does not invoke Maven. Related sample workspace: [`standalone-java/`](standalone-java/).

## Demo 2 — Verification and repair

Shows a deterministic verifier failure, diagnostic feedback, a fresh read, a repair mutation, and a later verification PASS:

```powershell
$repo = (Get-Location).Path
mvn "-Dmaven.repo.local=$repo\.m2\repository" "-Dtest=PostEditVerificationAgentTest#verificationFailureFeedsDiagnosticsThenRepairPasses" test
```

The verifier and model responses are scripted; the synthetic diagnostic is not a live model result. See [`standalone-java/`](standalone-java/) for a small standalone workspace fixture.

## Demo 3 — Environment-aware Maven tool

Shows that Maven is hidden in a standalone workspace and available with a safe root POM, with dispatch-time checks covered by tests:

```powershell
$repo = (Get-Location).Path
mvn "-Dmaven.repo.local=$repo\.m2\repository" "-Dtest=EnvironmentToolAvailabilityTest" test
```

No Maven process or model provider is required by the environment-availability cases using the recording process runner. [`maven-java/`](maven-java/) contains a small Maven workspace fixture for inspection; the focused unit test owns its own isolated temporary POM.

## Fixture folders

- `standalone-java/` — Java source without a POM; production policy uses the standalone Java verifier when available.
- `maven-java/` — Java source and a root POM; the presence of the safe regular root POM makes `run_maven_test` available.
- `python/` — a small Python source example; verification requires a local Python installation.

Fixtures are not auto-edited by the offline demos. For optional interactive use, work in a disposable copy and configure a provider as described in the root README. Live outcomes are stochastic and may incur provider charges.
