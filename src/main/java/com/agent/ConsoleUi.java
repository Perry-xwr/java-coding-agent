package com.agent;

import com.agent.agent.AgentEventListener;
import com.agent.tool.ToolResult;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public final class ConsoleUi implements AgentEventListener {
    private static final int MAX_SUMMARY_LENGTH = 120;

    private final PrintStream output;

    public ConsoleUi(PrintStream output) {
        this.output = Objects.requireNonNull(output, "output must not be null");
    }

    public void printBanner(Path workspace, CliMode mode) {
        output.println("Java Coding Agent");
        output.println();
        output.println("Workspace: " + workspace.toAbsolutePath().normalize());
        output.println("Provider: GLM");
        output.println("Coding Strategy: REACT_DIAGNOSTIC_RECOVERY");
        output.println("Mode: " + mode);
        output.println();
        output.println("Type /help for commands.");
        output.println();
    }

    public void promptUser(CliMode mode) {
        output.print("[" + mode + "] You > ");
        output.flush();
    }

    public void printAgentMessage(String message) {
        output.println("Agent >");
        output.println(message == null ? "" : message);
        output.println();
    }

    @Override
    public void assistantMessageStarted() {
        output.print("Agent > ");
        output.flush();
    }

    @Override
    public void assistantTextDelta(String delta) {
        output.print(delta);
        output.flush();
    }

    @Override
    public void assistantMessageFinished() {
        output.println();
        output.println();
        output.flush();
    }

    public void printSystemMessage(String message) {
        output.println("System > " + message);
    }

    @Override
    public void toolStarted(String toolName, Map<String, Object> arguments) {
        String summary = summarize(toolName, arguments);
        output.println("Tool > " + toolName + (summary.isEmpty() ? "" : "(" + summary + ")") + " ...");
    }

    @Override
    public void toolFinished(String toolName, ToolResult result) {
        if (result.success()) {
            output.println("Tool > " + toolName + " [OK]");
            return;
        }
        String code = result.errorCode() == null ? "FAILED" : result.errorCode().name();
        output.println("Tool > " + toolName + " [FAIL] " + code);
    }

    private static String summarize(String toolName, Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        return switch (toolName) {
            case "list_files", "read_file", "apply_patch" -> quoted(safeArguments.get("path"));
            case "search_code" -> quoted(first(safeArguments, "keyword", "query"));
            case "replace_lines" -> lineRange(safeArguments);
            case "run_maven_test" -> testSelector(safeArguments);
            default -> "";
        };
    }

    private static String lineRange(Map<String, Object> arguments) {
        String path = quoted(arguments.get("path"));
        Object start = arguments.get("startLine");
        Object end = arguments.get("endLine");
        if (start == null || end == null) {
            return path;
        }
        return path + (path.isEmpty() ? "" : ", ") + "lines " + start + "-" + end;
    }

    private static String testSelector(Map<String, Object> arguments) {
        Object testClass = arguments.get("testClass");
        Object testMethod = arguments.get("testMethod");
        if (testClass == null) {
            return "";
        }
        return quoted(testMethod == null ? testClass : testClass + "#" + testMethod);
    }

    private static Object first(Map<String, Object> arguments, String first, String second) {
        Object value = arguments.get(first);
        return value == null ? arguments.get(second) : value;
    }

    private static String quoted(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString().replace('\r', ' ').replace('\n', ' ');
        if (text.length() > MAX_SUMMARY_LENGTH) {
            text = text.substring(0, MAX_SUMMARY_LENGTH) + "…";
        }
        return "\"" + text.replace("\"", "\\\"") + "\"";
    }
}
