# Benchmark Quality Calibration Report

## 1. Motivation

The real `REACT_DIAGNOSTIC_RECOVERY` DEV run exposed two measurement-quality problems. In `multi_001`, hidden Maven tests passed, but a semantically equivalent loop was rejected because it used `<= Math.max(first, second)` rather than the expected substring `<= end`. In `logic_001`, a Java duplicate-variable compilation failure was decoded as mojibake and classified as `UNKNOWN`.

This calibration changes only evaluator semantics, subprocess encoding, and diagnostic parsing. It does not change the Agent prompt, runtime guards, strategy, tools, model, task difficulty, split, fixtures, hidden tests, or maximum steps.

## 2. Evaluator Audit

All 20 `v0.1` tasks were reviewed in [EVALUATOR_QUALITY_AUDIT.md](EVALUATOR_QUALITY_AUDIT.md). Every task had a configured substring check. Twelve ordinary bug, logic, or multi-step tasks carried false-negative risk because behaviorally equivalent solutions could miss the expected source form. Seven constraints are justified and remain strict: four tasks explicitly require test-file repair and three explicitly require named helper extraction. `bugfix_001` is behavior-only under MAVEN_TEST.

## 3. Evaluator Changes

`EvaluationSpec` now declares `strictContentCheck`. For COMBINED evaluation, readable expected files, an actual expected-file change, and passing hidden Maven tests are always required. Content checks gate success only when the task marks them strict. Non-strict content mismatches remain recorded as diagnostics.

`EvaluationResult` now persists `behavioralPassed`, `contentCheckPassed`, `strictContentRequired`, and `finalSuccess` semantics in addition to the existing fields and metrics. This makes `behavior PASS / content FAIL` directly observable.

## 4. Tasks Changed

The following 12 COMBINED tasks now treat content checks as non-strict:

`bugfix_002`, `bugfix_003`, `bugfix_004`, `bugfix_005`, `logic_001`, `logic_002`, `logic_003`, `logic_004`, `multi_001`, `multi_002`, `multi_003`, and `multi_004`.

Strict checks remain for `testfix_001` through `testfix_004` and `refactor_001` through `refactor_003`. The former constrain the requested test artifact; the latter constrain helper names explicitly stated by the task.

## 5. False-Negative Regression Tests

`EvaluatorSemanticsTest` covers:

1. hidden tests pass, content differs, and strict mode is false → success;
2. hidden tests pass, content differs, and strict mode is true → failure;
3. content passes but hidden tests fail → failure;
4. evaluator process infrastructure fails → IOException for separate evaluator-error handling.

The suite also verifies all 20 tasks have specs and exactly seven specs are strict.

## 6. Encoding Root Cause

`DefaultProcessRunner` already decoded captured bytes as UTF-8, but the Windows Maven/JDK subprocess was not told to emit UTF-8. Localized compiler output could therefore be emitted in a Windows console encoding and then decoded as UTF-8, producing replacement characters.

The runner now adds explicit Java subprocess options through inherited `JAVA_TOOL_OPTIONS`: `-Dfile.encoding=UTF-8`, `-Dsun.stdout.encoding=UTF-8`, and `-Dsun.stderr.encoding=UTF-8`. Existing options are preserved. Capture remains bounded, uses a fixed working directory, invokes the command directly without a shell, merges stderr deterministically, and decodes with UTF-8.

## 7. Diagnostic Parser Changes

Classification precedence is now ASSERTION → SYNTAX → COMPILATION → UNKNOWN. Compilation detection no longer depends on an English detail message: source-location records, `COMPILATION ERROR`, `Compilation failure`, `maven-compiler-plugin`, or `cannot find symbol` are sufficient. Corrupted localized detail is omitted from the concise summary rather than repeated.

`MavenDiagnostic` includes a bounded `rawFragment` (maximum 1,000 characters) containing relevant error lines. The full captured Maven output remains stored once in `ToolResult.output`; the fragment supports parser debugging without duplicating a giant log.

## 8. logic_001 Regression Fixture Result

Two secret-free resources model the observed failure. The normalized UTF-8 fixture contains a localized duplicate-variable message and is classified as `COMPILATION`, with `MaxFinder.java`, line 8, a non-empty concise summary, and a bounded raw fragment. The historical mojibake fixture also falls back to `COMPILATION`; its concise summary is `Compilation error in MaxFinder.java:8` and does not propagate replacement characters.

A real Java subprocess test emits Chinese text and verifies UTF-8 capture without mojibake.

## 9. Parser Classification Before vs After

| Input | Before | After |
|---|---|---|
| `logic_001`-style localized compiler failure | UNKNOWN | COMPILATION |
| Same structural compiler failure with corrupted detail | UNKNOWN | COMPILATION with clean fallback summary |
| Missing symbol | COMPILATION | COMPILATION |
| Recognized syntax marker | SYNTAX | SYNTAX |
| Surefire/JUnit expected-vs-actual | ASSERTION | ASSERTION |

## 10. Existing Tests

Final offline Maven result:

```text
Tests run: 103, Failures: 0, Errors: 0, Skipped: 4
BUILD SUCCESS
```

The four skips remain the existing intentional environment-gated/live or Windows capability skips. No GLM request or benchmark API call was made.

## 11. Offline Re-evaluation

The preserved final workspace for `multi_001` was available. Its recorded source was left unchanged, its injected hidden test was present, and an offline Maven run of `bench.RangeSumHiddenTest` passed with exit code 0. Under calibrated semantics:

```text
behavioralPassed=true
contentCheckPassed=false
strictContentRequired=false
finalSuccess=true
```

The independent record is stored at `benchmark/reanalysis/20260924-evaluator-quality-audit/reevaluation.json`. The original experiment and its `evaluation.json` were not modified. No GLM call and no benchmark rerun occurred.

## 12. Remaining Risks

- `JAVA_TOOL_OPTIONS` and `sun.stdout/stderr.encoding` are practical JDK 17 controls, but localized third-party native processes may use their own encoding.
- Substring checks remain intentionally simple and can still produce diagnostic noise on non-strict tasks.
- Strict test-fix checks verify the requested artifact and key content, not a complete semantic diff policy.
- Only the preserved `multi_001` workspace was re-evaluated; this is not a replacement for a future calibrated full DEV measurement.

## 13. Next Step

After review, run exactly one calibrated measurement using `glm-4-flash`, DEV 6, and `REACT_DIAGNOSTIC_RECOVERY` with provider defaults and unchanged task-specific maximum steps. This report does not run it and does not enter TEST.
