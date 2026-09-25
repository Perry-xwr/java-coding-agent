package com.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

import com.agent.agent.AgentRunResult;
import com.agent.agent.TerminationReason;

public final class InteractiveCli {
    /** Wait briefly for a second line before treating a normal submission as a paste. */
    private static final Duration INITIAL_PASTE_WINDOW = Duration.ofMillis(120);
    /** Once a paste is underway, wait for this quiet period after every received line. */
    private static final Duration PASTE_SILENT_WINDOW = Duration.ofMillis(200);
    private static final Duration INPUT_POLL_INTERVAL = Duration.ofMillis(5);
    private static final List<String> CONTEXTUAL_FOLLOW_UPS = List.of(
            "是", "是的", "对", "可以", "好的", "继续", "继续吧", "读吧", "读取吧", "修改吧", "那就做吧", "就这样",
            "yes", "ok", "continue", "go ahead", "do it"
    );
    private final CliSessions sessions;
    private final ConsoleUi ui;
    private final Path workspace;
    private final CliIntentRouter intentRouter;
    private final CliWorkingContext workingContext;

    private CliMode activeMode = CliMode.AUTO;
    private CliMode lastAutoRoutedMode;
    private String pendingLine;

    public InteractiveCli(CliSessions sessions, ConsoleUi ui, Path workspace) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.ui = Objects.requireNonNull(ui, "ui must not be null");
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null")
                .toAbsolutePath()
                .normalize();
        this.intentRouter = new CliIntentRouter();
        this.workingContext = new CliWorkingContext();
    }

    public void run(BufferedReader reader) throws IOException {
        Objects.requireNonNull(reader, "reader must not be null");
        ui.printBanner(workspace, activeMode);
        while (true) {
            ui.promptUser(activeMode);
            String line = readLine(reader);
            if (line == null) {
                return;
            }
            if (line.trim().equalsIgnoreCase("/begin")) {
                String block = readExplicitBlock(reader);
                if (block == null) {
                    return;
                }
                if (block.trim().isEmpty()) {
                    ui.finishEmptyInput();
                    continue;
                }
                runAgent(block);
                continue;
            }
            String input = line.trim();
            if (input.isEmpty()) {
                ui.finishEmptyInput();
                continue;
            }
            String command = input.toLowerCase(Locale.ROOT);
            if (command.equals("exit") || command.equals("quit") || command.equals("/exit")) {
                ui.printSystemMessage("Goodbye.");
                return;
            }
            if (command.equals("/chat")) {
                switchMode(CliMode.CHAT);
                continue;
            }
            if (command.equals("/read")) {
                switchMode(CliMode.READ);
                continue;
            }
            if (command.equals("/code")) {
                switchMode(CliMode.CODE);
                continue;
            }
            if (command.equals("/auto")) {
                switchMode(CliMode.AUTO);
                continue;
            }
            if (command.equals("/help")) {
                printHelp();
                continue;
            }
            if (command.equals("clear")) {
                if (activeMode == CliMode.AUTO) {
                    sessions.clearAll();
                    lastAutoRoutedMode = null;
                    workingContext.clear();
                    ui.printSystemMessage("AUTO histories cleared.");
                } else {
                    sessions.session(activeMode).clearHistory();
                    ui.printSystemMessage(activeMode + " history cleared.");
                }
                continue;
            }
            runAgent(readPastedBlock(reader, line));
        }
    }

    /**
     * Reads an explicit block without interpreting commands inside it. A null result means the input ended
     * before its required /end marker.
     */
    private String readExplicitBlock(BufferedReader reader) throws IOException {
        List<String> lines = new ArrayList<>();
        while (true) {
            String line = readLine(reader);
            if (line == null) {
                return null;
            }
            if (line.trim().equalsIgnoreCase("/end")) {
                return String.join(System.lineSeparator(), lines);
            }
            lines.add(line);
        }
    }

    private String readPastedBlock(BufferedReader reader, String firstLine) throws IOException {
        StringBuilder block = new StringBuilder(firstLine);
        FenceState fence = FenceState.from(firstLine);
        if (fence.open()) {
            return readUntilFenceClosed(reader, block, fence);
        }

        boolean pasteCaptureStarted = false;
        long deadline = System.nanoTime() + INITIAL_PASTE_WINDOW.toNanos();
        while (System.nanoTime() < deadline) {
            if (!reader.ready()) {
                LockSupport.parkNanos(INPUT_POLL_INTERVAL.toNanos());
                continue;
            }
            String line = readLine(reader);
            if (line == null) {
                break;
            }
            if (!pasteCaptureStarted && isCommand(line)) {
                pendingLine = line;
                break;
            }
            appendLine(block, line);
            pasteCaptureStarted = true;
            fence.accept(line);
            if (fence.open()) {
                return readUntilFenceClosed(reader, block, fence);
            }
            deadline = System.nanoTime() + PASTE_SILENT_WINDOW.toNanos();
        }
        return block.toString();
    }

    private static String readUntilFenceClosed(BufferedReader reader, StringBuilder block, FenceState fence)
            throws IOException {
        while (fence.open()) {
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            appendLine(block, line);
            fence.accept(line);
        }
        return block.toString();
    }

    private void runAgent(String task) {
        try {
            CliMode sessionMode = resolveSessionMode(task);
            if (activeMode == CliMode.AUTO) {
                ui.printSystemMessage("AUTO -> " + sessionMode);
            }
            AgentRunResult result = sessions.session(sessionMode).runWithTrajectory(
                    taskWithWorkspaceContext(task)
            );
            workingContext.observe(result.trajectory());
            if (result.trajectory().terminationReason() == TerminationReason.LLM_ERROR) {
                throw new IOException("LLM request failed");
            }
            if (activeMode == CliMode.AUTO) {
                lastAutoRoutedMode = sessionMode;
            }
        } catch (IOException exception) {
            ui.printSystemMessage("LLM streaming failed: " + safeMessage(exception));
        }
    }

    private CliMode resolveSessionMode(String task) {
        if (activeMode != CliMode.AUTO) {
            return activeMode;
        }
        if (hasUsableContextualReference(task)) {
            if (intentRouter.hasClearMutationIntent(task)) {
                return CliMode.CODE;
            }
            if (intentRouter.hasReadRequest(task)) {
                return CliMode.READ;
            }
        }
        if (lastAutoRoutedMode != null && isContextualFollowUp(task)) {
            return lastAutoRoutedMode;
        }
        RoutingDecision decision = intentRouter.route(task);
        if (decision.confidence() == RoutingConfidence.LOW && lastAutoRoutedMode != null) {
            return lastAutoRoutedMode;
        }
        return decision.mode();
    }

    private boolean hasUsableContextualReference(String task) {
        return workingContext.hasContextualFileReference(task)
                && !intentRouter.hasExplicitWorkspaceTarget(task)
                && (!workingContext.lastResolvedFiles().isEmpty());
    }

    private String taskWithWorkspaceContext(String task) {
        if (activeMode != CliMode.AUTO || !hasUsableContextualReference(task)) {
            return task;
        }
        String context = workingContext.contextualPrompt();
        return context.isBlank() ? task : context + "\n\nUser request:\n" + task;
    }

    private static boolean isContextualFollowUp(String input) {
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        return CONTEXTUAL_FOLLOW_UPS.contains(normalized);
    }

    private String readLine(BufferedReader reader) throws IOException {
        if (pendingLine != null) {
            String line = pendingLine;
            pendingLine = null;
            return line;
        }
        return reader.readLine();
    }

    private static boolean isCommand(String line) {
        String command = line.trim().toLowerCase(Locale.ROOT);
        return command.equals("exit")
                || command.equals("quit")
                || command.equals("/exit")
                || command.equals("/chat")
                || command.equals("/read")
                || command.equals("/code")
                || command.equals("/auto")
                || command.equals("/help")
                || command.equals("clear")
                || command.equals("/begin");
    }

    private static void appendLine(StringBuilder block, String line) {
        block.append(System.lineSeparator()).append(line);
    }

    private static String safeMessage(IOException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.replaceAll("(?i)Bearer\\s+\\S+", "Bearer [REDACTED]");
    }

    private void switchMode(CliMode mode) {
        activeMode = mode;
        lastAutoRoutedMode = null;
        ui.printSystemMessage("Switched to " + mode + " mode.");
    }

    private void printHelp() {
        ui.printSystemMessage("Commands:");
        ui.printSystemMessage("/chat   General conversation");
        ui.printSystemMessage("/read   Read/search workspace");
        ui.printSystemMessage("/code   Modify and test code");
        ui.printSystemMessage("/auto   Route each message to CHAT, READ, or CODE");
        ui.printSystemMessage("/help   Show commands");
        ui.printSystemMessage("/begin  Start an explicit multiline message; finish it with /end");
        ui.printSystemMessage("/exit   Exit");
    }

    private static final class FenceState {
        private int backtickCount;

        private static FenceState from(String line) {
            FenceState state = new FenceState();
            state.accept(line);
            return state;
        }

        private boolean open() {
            return backtickCount > 0;
        }

        private void accept(String line) {
            String trimmed = line.stripLeading();
            int count = leadingBackticks(trimmed);
            if (!open()) {
                if (count >= 3) {
                    backtickCount = count;
                }
                return;
            }
            if (count == backtickCount && trimmed.substring(count).trim().isEmpty()) {
                backtickCount = 0;
            }
        }

        private static int leadingBackticks(String value) {
            int index = 0;
            while (index < value.length() && value.charAt(index) == '`') {
                index++;
            }
            return index;
        }
    }
}
