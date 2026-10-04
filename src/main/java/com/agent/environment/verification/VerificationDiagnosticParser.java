package com.agent.environment.verification;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, defensive extraction of common Python, Node, javac and gcc/clang coordinates. */
public final class VerificationDiagnosticParser {
    private static final Pattern PYTHON = Pattern.compile("(?i)line\\s+(\\d+)(?:,\\s*column\\s+(\\d+))?");
    private static final Pattern FILE_LINE_COLUMN = Pattern.compile("(?:^|[\\r\\n])[^\\r\\n]*?:(\\d+):(\\d+):");
    private static final Pattern FILE_LINE = Pattern.compile("(?:^|[\\r\\n])[^\\r\\n]*?:(\\d+)(?::\\s|: error|: warning)");
    private static final Pattern NODE_COLUMN = Pattern.compile("(?:\\(|:)(\\d+):(\\d+)\\)");
    private static final Pattern NODE_LINE = Pattern.compile("(?:^|[\\r\\n])[^\\r\\n]*?:(\\d+)(?:\\s|$)");

    private VerificationDiagnosticParser() { }

    public static DiagnosticLocation parse(String verifierId, String diagnostic) {
        String id = verifierId == null ? "" : verifierId.toLowerCase(java.util.Locale.ROOT);
        String text = diagnostic == null ? "" : diagnostic;
        Pattern selected = id.contains("python") ? PYTHON
                : id.contains("node") || id.contains("javascript") ? NODE_COLUMN
                : id.contains("gcc") || id.contains("gxx") || id.contains("cpp") ? FILE_LINE_COLUMN
                : FILE_LINE;
        Matcher matcher = selected.matcher(text);
        if (!matcher.find() && (selected == FILE_LINE || selected == FILE_LINE_COLUMN)) {
            matcher = FILE_LINE.matcher(text);
            if (!matcher.find()) return DiagnosticLocation.unknown();
            return new DiagnosticLocation(number(matcher, 1), 0);
        }
        if (!matcher.find(0) && (selected == NODE_COLUMN)) {
            matcher = NODE_LINE.matcher(text);
            if (!matcher.find()) return DiagnosticLocation.unknown();
            return new DiagnosticLocation(number(matcher, 1), 0);
        }
        if (!matcher.find(0)) return DiagnosticLocation.unknown();
        return new DiagnosticLocation(number(matcher, 1), number(matcher, 2));
    }

    private static int number(Matcher matcher, int group) {
        try { return matcher.group(group) == null ? 0 : Integer.parseInt(matcher.group(group)); }
        catch (RuntimeException ignored) { return 0; }
    }
}
