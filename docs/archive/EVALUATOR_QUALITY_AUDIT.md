# Evaluator Quality Audit

## Scope and Method

This audit covers all 20 tasks in benchmark `v0.1`. A source-shape constraint means any `requiredContains` or `forbiddenContains` rule. Risk is marked when an ordinary behavioral repair could satisfy hidden tests through an equivalent implementation but still miss the configured substring. No task difficulty, split, fixture, hidden test, model, or maximum-step setting was changed.

Classification:

- `CONTENT_SHAPE_REQUIRED`: the task explicitly requires a particular artifact or named structural refactor.
- `CONTENT_SHAPE_OPTIONAL`: content checks remain useful diagnostics but do not override passing hidden behavioral tests.
- `BEHAVIOR_ONLY`: hidden tests are the success criterion; any configured content check is non-gating.

## Twenty-Task Audit

| Task ID | Evaluation type | Hidden test | Content check | Expected files | Shape classification | Behavioral requirement | False-negative risk | Change made | Rationale |
|---|---|---|---|---|---|---|---|---|---|
| `bugfix_001` | MAVEN_TEST | Calculator addition | `a + b`; forbid `a - b` | `Calculator.java` | BEHAVIOR_ONLY | Return operand sum | Low | None; content stays diagnostic-only | MAVEN_TEST already ignores source shape |
| `bugfix_002` | COMBINED | Adult boundary | `>= 18`; forbid `> 18` | `AgePolicy.java` | CONTENT_SHAPE_OPTIONAL | Accept age 18 | Medium | Made non-strict | Equivalent predicates can satisfy behavior |
| `bugfix_003` | COMBINED | Value equality and nulls | `Objects.equals`; forbid identity comparison | `TokenMatcher.java` | CONTENT_SHAPE_OPTIONAL | Null-safe value equality | High | Made non-strict | Null-safe ternary or manual equality is valid |
| `bugfix_004` | COMBINED | All array elements, no overrun | `< length`; forbid `<= length` | `ArrayTotal.java` | CONTENT_SHAPE_OPTIONAL | Correct bounded sum | Medium | Made non-strict | Enhanced-for is equivalent |
| `bugfix_005` | COMBINED | Colon parsing and trimming | `split(":", 2)`; forbid comma split | `KeyValueParser.java` | CONTENT_SHAPE_OPTIONAL | Split once on colon and trim | High | Made non-strict | `indexOf`/substring is equivalent |
| `logic_001` | COMBINED | Negative arrays and empty rejection | `values[0]`; forbid `max = 0` | `MaxFinder.java` | CONTENT_SHAPE_OPTIONAL | Correct maximum and empty handling | High | Made non-strict | `Integer.MIN_VALUE` is equivalent |
| `logic_002` | COMBINED | Ordered deduplication | `LinkedHashSet` | `UniqueNames.java` | CONTENT_SHAPE_OPTIONAL | Preserve first-seen order | High | Made non-strict | Manual ordered filtering is equivalent |
| `logic_003` | COMBINED | Discount boundary | `>= 100`; forbid `> 100` | `DiscountPolicy.java` | CONTENT_SHAPE_OPTIONAL | Discount exactly 100 | Medium | Made non-strict | Equivalent branching can pass behavior |
| `logic_004` | COMBINED | Space/case normalization and null | `replace`, `toLowerCase` | `PalindromeChecker.java` | CONTENT_SHAPE_OPTIONAL | Normalized palindrome behavior | High | Made non-strict | Regex/filter/case-insensitive loops are valid |
| `testfix_001` | COMBINED | Production behavior remains correct | expected assertion 5; forbid 4 | `CalculatorTest.java` | CONTENT_SHAPE_REQUIRED | Repair the specified test expectation | Low | Kept strict | Task explicitly requires changing the test artifact |
| `testfix_002` | COMBINED | Production greeting remains correct | `Hello, Ada`; forbid `Hi, Ada` | `GreetingTest.java` | CONTENT_SHAPE_REQUIRED | Repair the documented greeting test | Low | Kept strict | Prevents changing production code instead |
| `testfix_003` | COMBINED | Stack behavior remains correct | expected size 1; forbid 0 | `SimpleStackTest.java` | CONTENT_SHAPE_REQUIRED | Repair the specified test | Low | Kept strict | Expected file and assertion are task-explicit |
| `testfix_004` | FILE_CONTENT | Not executed by evaluator type | `assertTrue`; forbid `assertFalse` | `EvenNumbersTest.java` | CONTENT_SHAPE_REQUIRED | Correct the assertion for 4 | Low | Kept strict | Content is the only deterministic criterion |
| `refactor_001` | COMBINED | Tax behavior unchanged | `applyTax` | `PriceCalculator.java` | CONTENT_SHAPE_REQUIRED | Extract named helper | Low | Kept strict | Method name is explicit in task text |
| `refactor_002` | COMBINED | Formatting behavior unchanged | `normalize` | `NameFormatter.java` | CONTENT_SHAPE_REQUIRED | Extract named helper | Low | Kept strict | Method name is explicit in task text |
| `refactor_003` | COMBINED | Exception behavior unchanged | `requireItem` | `OrderService.java` | CONTENT_SHAPE_REQUIRED | Extract named helper | Low | Kept strict | Method name is explicit in task text |
| `multi_001` | COMBINED | Reversed inclusive bounds | `Math.min`, `Math.max`, `<= end` | `RangeSum.java` | CONTENT_SHAPE_OPTIONAL | Support either order and both endpoints | High | Made non-strict | Real run passed hidden tests with `<= Math.max(...)` |
| `multi_002` | COMBINED | All trimmed CSV columns | `parts.length`, `trim`; forbid `length - 1` | `CsvRowParser.java` | CONTENT_SHAPE_OPTIONAL | Return every trimmed column | High | Made non-strict | Streams and enhanced-for are equivalent |
| `multi_003` | COMBINED | Reject negatives and total quantities | negative check, `+= quantity`; forbid `total++` | `Inventory.java` | CONTENT_SHAPE_OPTIONAL | Validation and correct aggregate | High | Made non-strict | Recomputed totals or other updates are equivalent |
| `multi_004` | COMBINED | Age and email boundaries | exact comparison fragments | `UserValidator.java` | CONTENT_SHAPE_OPTIONAL | Validate age and non-empty email parts | High | Made non-strict | Regex or split-based validation is equivalent |

## Audit Totals

- Tasks with configured source-shape constraints: **20/20**.
- Tasks assessed as meaningful false-negative risks: **12/20**.
- Strict content tasks retained: **7/20** (four TEST_FIX and three named-refactor tasks).
- Behavior-only task: **1/20** (`bugfix_001`, MAVEN_TEST).
- Combined tasks changed from content-gating to behavior-primary: **12/20**.

The risk count excludes `bugfix_001`, whose MAVEN_TEST evaluator already ignores its content check, and excludes seven tasks whose requested artifact or named structure makes the constraint task-explicit.
