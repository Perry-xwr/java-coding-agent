# Demo: Palindrome Logic Repair

This example is drawn from the successful held-out task `logic_004`, not a toy arithmetic prompt.

## User Task

> Fix `PalindromeChecker` so comparison ignores spaces and letter case while still handling null.

## Observable Tool Sequence

```text
search_code
    ↓
read_file
    ↓
search_code
    ↓
apply_patch
    ↓
run_maven_test
    ↓
final answer
```

## Modification

The Agent inspected the existing implementation, identified the normalization and null-handling requirements, and applied an exact single-match edit to the existing source file. The changed behavior normalized spaces and letter case while preserving safe null handling.

## Validation

The controlled Maven test call succeeded. The hidden deterministic evaluator then tested the held-out behavior and marked the task successful. The trajectory completed in six recorded steps with five tool calls.

This example shows the intended V1 flow—inspect, edit, validate, and report—without exposing hidden chain-of-thought or hidden test source.
