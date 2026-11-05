package com.agent;

import com.agent.agent.AgentEventListener;
import com.agent.agent.PlanningMode;
import com.agent.agent.TaskEvidence;
import com.agent.agent.TaskChangeReview;
import com.agent.llm.ModelRequestUsageSummary;
import com.agent.tool.ToolResult;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public final class ConsoleUi implements AgentEventListener {
    private static final int MAX_SUMMARY_LENGTH = 120;

    private final PrintStream output;
    private final String provider;
    private final String model;
    private java.util.function.Consumer<String> messageRenderer;
    void withMessageRenderer(java.util.function.Consumer<String> renderer, Runnable action) {
        var previous = messageRenderer;
        messageRenderer = renderer;
        try { action.run(); } finally { messageRenderer = previous; }
    }

    public ConsoleUi(PrintStream output) {
        this(output,"UNKNOWN (injected client)","UNKNOWN");
    }
    public ConsoleUi(PrintStream output,String provider,String model) {
        this.output = Objects.requireNonNull(output, "output must not be null");
        this.provider=Objects.requireNonNull(provider);
        this.model=Objects.requireNonNull(model);
    }

    public void printBanner(Path workspace, CliMode mode) {
        printBanner(workspace, mode, null);
    }

    public void printBanner(Path workspace, CliMode mode, PlanningMode planningMode) {
        output.println("Java Coding Agent");
        output.println("Workspace: " + workspace.toAbsolutePath().normalize());
        output.println("Provider: " + provider);
        output.println("Model: " + model);
        output.println("Mode: " + displayMode(mode));
    }

    public void promptUser(CliMode mode) {
        output.print("[" + displayMode(mode) + "] You > ");
        output.flush();
    }

    private static String displayMode(CliMode mode) {
        return mode == CliMode.CODE ? "UNIFIED" : mode.name();
    }

    /** Ensures a blank submission does not cause two prompts to share one output line. */
    public void finishEmptyInput() {
        output.println();
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
        if (messageRenderer != null) messageRenderer.accept("System > " + message);
        else output.println("System > " + message);
    }

    public void printUsageSummary(ModelRequestUsageSummary summary) {
        Objects.requireNonNull(summary, "summary must not be null");
        output.println("System > Model usage: requests=" + summary.requests().size()
                + ", input=" + number(summary.inputTokens())
                + ", output=" + number(summary.outputTokens())
                + ", total=" + number(summary.totalTokens())
                + ", cache hit=" + number(summary.cacheHitTokens())
                + ", max request payload=" + (summary.requests().isEmpty()
                ? "UNKNOWN" : summary.maxPayloadBytes() + " B")
                + ", usage=" + (summary.usageComplete() ? "COMPLETE" : "INCOMPLETE/UNKNOWN"));
    }

    public void printTaskEvidence(TaskEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        String verification = evidence.verificationKind() == TaskEvidence.VerificationKind.NONE
                ? evidence.verificationStatus().name()
                : evidence.verificationKind() + "=" + evidence.verificationStatus().name();
        output.println("System > Task evidence: execution=" + evidence.executionStatus()
                + ", mutation=" + evidence.mutationStatus()
                + ", verification=" + verification
                + ", functional=" + evidence.functionalVerification()
                + ", assessment=" + evidence.completionAssessment());
    }

    public void printTaskChanges(TaskChangeReview review) {
        Objects.requireNonNull(review, "review must not be null");
        if (review.changedFiles().isEmpty()) return;
        long uncertain = review.changedFiles().stream().filter(file ->
                file.attribution() == TaskChangeReview.Attribution.UNCERTAIN).count();
        long attributed = review.changedFiles().size() - uncertain;
        int additions = 0;
        int deletions = 0;
        for (TaskChangeReview.FileChange file : review.changedFiles()) {
            if (file.attribution() == TaskChangeReview.Attribution.UNCERTAIN) continue;
            for (String line : file.diff().split("\\R")) {
                if (line.startsWith("+") && !line.startsWith("+++")) additions++;
                else if (line.startsWith("-") && !line.startsWith("---")) deletions++;
            }
        }
        output.println("System > Changes: " + review.changedFiles().size() + " files changed, "
                + attributed + " attributed to tool execution, " + uncertain + " uncertain; "
                + additions + " insertions, " + deletions + " deletions. Use /diff to review.");
    }

    public void printDiff(TaskChangeReview review) {
        Objects.requireNonNull(review, "review must not be null");
        if (review.changedFiles().isEmpty()) {
            printSystemMessage("No captured file changes in the latest task.");
            return;
        }
        for (TaskChangeReview.FileChange file : review.changedFiles()) {
            printSystemMessage(safeTerminalText(file.path()) + " [" + file.type() + ", " + file.attribution()
                    + ", verification=" + file.verificationStatus() + "]");
            output.print(safeDiff(file.diff()));
            if (!file.diff().endsWith("\n")) output.println();
        }
        if (!review.diffComplete()) printSystemMessage("Diff output was truncated; full diff is not available.");
    }

    private static String number(Integer value) {
        return value == null ? "UNKNOWN" : value.toString();
    }

    @Override
    public void toolStarted(String toolName, Map<String, Object> arguments) {
        String summary = summarize(toolName, arguments);
        output.println("Tool > " + toolName + (summary.isEmpty() ? "" : "(" + summary + ")") + " ...");
    }

    @Override
    public void toolFinished(String toolName, ToolResult result) {
        if (toolName.equals("run_maven_test")) {
            Object status = result.metadata().get("testStatus");
            String safeStatus = status instanceof String s && s.matches("[A-Z_]{1,48}") ? s : "UNKNOWN";
            output.println("Tool > Maven result=" + safeStatus + ", executed="
                    + Boolean.TRUE.equals(result.metadata().get("executed"))
                    + ", tests=" + count(result, "testsRun") + ", failed=" + count(result, "testsFailed")
                    + ", errors=" + count(result, "testsErrors") + ", skipped=" + count(result, "testsSkipped"));
        }
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
            case "list_files", "read_file", "apply_patch", "apply_context_patch", "create_file" ->
                    quoted(safeArguments.get("path"));
            case "search_code" -> quoted(first(safeArguments, "keyword", "query"));
            case "find_files" -> quoted(safeArguments.get("pattern"));
            case "replace_lines" -> lineRange(safeArguments);
            case "run_maven_test" -> testSelector(safeArguments);
            default -> "";
        };
    }

    private static String count(ToolResult result, String key) {
        Object value = result.metadata().get(key);
        return value instanceof Integer number && number >= 0 ? number.toString() : "UNKNOWN";
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

    private static String safeTerminalText(String value) {
        return value.replaceAll("[\\p{Cntrl}]", "?");
    }

    /** Prevents workspace text from injecting terminal control sequences in the read-only preview. */
    private static String safeDiff(String value) {
        StringBuilder safe = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) && character != '\n' && character != '\r' && character != '\t') {
                safe.append(String.format("\\u%04x", (int) character));
            } else {
                safe.append(character);
            }
        }
        return safe.toString();
    }
}
