package com.agent.tool;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MavenDiagnosticParser {
    private static final int RAW_FRAGMENT_LIMIT = 1_000;
    private static final Pattern SOURCE = Pattern.compile(
            "(?m)^\\[ERROR\\]\\s+(.+\\.java):\\[(\\d+),(\\d+)\\]\\s*(.*)$"
    );
    private static final Pattern SYMBOL = Pattern.compile(
            "(?m)^\\[ERROR\\]\\s+symbol:\\s*(.+)$"
    );
    private static final Pattern TEST = Pattern.compile(
            "(?m)^\\[ERROR\\]\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s+--"
    );
    private static final Pattern ASSERTION = Pattern.compile(
            "expected:\\s*<([^>]*)>\\s*but was:\\s*<([^>]*)>",
            Pattern.CASE_INSENSITIVE
    );
    private static final List<String> SYNTAX_MARKERS = List.of(
            "';' expected",
            "illegal start of expression",
            "reached end of file while parsing",
            "')' expected",
            "'}' expected",
            "unclosed string literal"
    );

    public MavenDiagnostic parse(String output) {
        String text = output == null ? "" : output;
        String rawFragment = rawFragment(text);
        Matcher source = SOURCE.matcher(text);
        String file = null;
        Integer line = null;
        String sourceMessage = null;
        if (source.find()) {
            file = source.group(1).trim();
            line = Integer.valueOf(source.group(2));
            sourceMessage = source.group(4).trim();
        }

        Matcher assertion = ASSERTION.matcher(text);
        if (assertion.find()) {
            Matcher test = TEST.matcher(text);
            String testClass = null;
            String testMethod = null;
            if (test.find()) {
                testClass = test.group(1);
                testMethod = test.group(2);
            }
            String expected = assertion.group(1);
            String actual = assertion.group(2);
            String summary = (testClass == null ? "Assertion failure" : testClass + "." + testMethod)
                    + ": expected <" + expected + "> but was <" + actual + ">";
            return new MavenDiagnostic(
                    DiagnosticType.ASSERTION, summary, null, null, null,
                    testClass, testMethod, expected, actual, "assertion failed", rawFragment
            );
        }

        String syntax = syntaxMessage(text, sourceMessage);
        if (syntax != null) {
            String summary = location(file, line) + syntax;
            return new MavenDiagnostic(
                    DiagnosticType.SYNTAX, summary, file, line, null,
                    null, null, null, null, syntax, rawFragment
            );
        }

        if (isCompilationFailure(text, file)) {
            Matcher symbolMatcher = SYMBOL.matcher(text);
            String symbol = symbolMatcher.find() ? symbolMatcher.group(1).trim() : null;
            String message = compilationMessage(text, sourceMessage, symbol);
            return new MavenDiagnostic(
                    DiagnosticType.COMPILATION,
                    compilationSummary(file, line, message),
                    file,
                    line,
                    symbol,
                    null,
                    null,
                    null,
                    null,
                    message,
                    rawFragment
            );
        }

        List<String> errors = text.lines()
                .map(String::trim)
                .filter(lineText -> lineText.startsWith("[ERROR]"))
                .map(lineText -> lineText.substring("[ERROR]".length()).trim())
                .filter(lineText -> !lineText.isBlank())
                .distinct()
                .limit(3)
                .toList();
        String summary = errors.isEmpty() ? "Maven test execution failed" : String.join(" | ", errors);
        return new MavenDiagnostic(
                DiagnosticType.UNKNOWN, summary, file, line, null,
                null, null, null, null, sourceMessage, rawFragment
        );
    }

    private static boolean isCompilationFailure(String text, String file) {
        String lower = text.toLowerCase();
        return file != null
                || lower.contains("compilation error")
                || lower.contains("compilation failure")
                || lower.contains("maven-compiler-plugin")
                || lower.contains("cannot find symbol");
    }

    private static String compilationMessage(String text, String sourceMessage, String symbol) {
        if (text.contains("cannot find symbol")) {
            return "cannot find symbol" + (symbol == null ? "" : ": " + symbol);
        }
        if (sourceMessage != null && !sourceMessage.isBlank() && !looksCorrupted(sourceMessage)) {
            return sourceMessage;
        }
        return null;
    }

    private static String compilationSummary(String file, Integer line, String message) {
        StringBuilder summary = new StringBuilder("Compilation error");
        if (file != null) {
            summary.append(" in ").append(fileName(file));
            if (line != null) {
                summary.append(':').append(line);
            }
        }
        if (message != null) {
            summary.append(": ").append(message);
        }
        return summary.toString();
    }

    private static String fileName(String file) {
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return slash < 0 ? file : file.substring(slash + 1);
    }

    private static boolean looksCorrupted(String value) {
        return value.indexOf('\uFFFD') >= 0 || value.contains("���");
    }

    private static String rawFragment(String text) {
        String relevant = text.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("[ERROR]")
                        || line.contains("AssertionFailedError"))
                .limit(8)
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
        if (relevant.length() <= RAW_FRAGMENT_LIMIT) {
            return relevant.isBlank() ? null : relevant;
        }
        return relevant.substring(0, RAW_FRAGMENT_LIMIT) + "...[truncated]";
    }

    private static String syntaxMessage(String output, String sourceMessage) {
        for (String marker : SYNTAX_MARKERS) {
            if (output.contains(marker)) {
                return marker;
            }
        }
        return sourceMessage != null && sourceMessage.toLowerCase().contains("expected")
                ? sourceMessage
                : null;
    }

    private static String location(String file, Integer line) {
        if (file == null) {
            return "";
        }
        return file + (line == null ? "" : ":" + line) + ": ";
    }
}
