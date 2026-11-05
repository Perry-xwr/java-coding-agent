package com.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

import com.agent.agent.AgentRunResult;
import com.agent.agent.TerminationReason;
import com.agent.agent.TaskChangeReview;
import com.agent.llm.LiveTestControl;
import com.agent.memory.*;

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
    private final CliSessionStore sessionStore;
    private final CliConversationManager conversationManager;
    private final ProjectInstructionLoader projectInstructionLoader = new ProjectInstructionLoader();

    // CODE is the unified default profile; AUTO remains available only as an explicit legacy mode.
    private CliMode activeMode = CliMode.CODE;
    private CliMode lastAutoRoutedMode;
    private String pendingLine;
    private boolean dirty;
    private final CliMode defaultMode;
    private boolean verbose;
    private AgentRunResult lastResult;
    private TerminalSessionInput terminalInput;
    // Only sessions created in this process before their first task are title-eligible.
    // Loaded/legacy names are never implicitly renamed; no persistence schema change.
    private final java.util.Set<String> untitledSessions = new java.util.HashSet<>();
    private TaskChangeReview lastTaskChanges = TaskChangeReview.empty("none");
    private final ExtractiveSessionSummarizer sessionSummarizer = new ExtractiveSessionSummarizer();
    private SessionSummary sessionSummary = SessionSummary.empty();
    private CrossSessionState crossState = CrossSessionState.off();
    void embeddingProviderForTest(com.agent.memory.EmbeddingProvider provider){conversationManager.embeddingProvider(provider);}
    private ExtractionState extraction = ExtractionState.empty();
    private record Reviewed(String sessionId,String candidateId,String fingerprint,ConflictDetector.Suggestion suggestion,String targetVersion){}
    private final java.util.Map<String,Reviewed> reviews=new java.util.HashMap<>();
    private boolean llmConflictEnabled;
    private boolean localMemorySaveFailed;
    private int conflictCalls;
    private final List<com.agent.llm.ProviderTokenUsage> conflictUsage=new ArrayList<>();

    private String memoryScopeId = java.util.UUID.randomUUID().toString();
    private List<SessionContextMetrics> lastMemoryRequests = List.of();
    private com.agent.llm.ModelRequestUsageSummary lastMemoryUsage = com.agent.llm.ModelRequestUsageSummary.empty();

    public InteractiveCli(CliSessions sessions, ConsoleUi ui, Path workspace) {
        this(sessions, ui, workspace, CliMode.CODE);
    }

    /** Local application-service boundary. No input loop, Agent run, banner or console approval. */
    boolean executeLocalMemory(CliConversation conversation,String action,boolean automatic) {
        localMemorySaveFailed=false;
        restoreConversationSnapshot(conversation.snapshot());
        conversationManager.activate(conversation);memoryScopeId=conversation.id();
        if(automatic){
            if(crossState.enabled()&&extraction.autoEnabled()&&!extraction.llmEnabled())harvestMemory(false);
        }else memoryCommand(action);
        return !localMemorySaveFailed;
    }
    void localMemoryEmbedding(EmbeddingProvider provider){conversationManager.embeddingProvider(Objects.requireNonNull(provider));}

    /** Offline Memory demonstration may explicitly start in CHAT; production remains UNIFIED. */
    InteractiveCli(CliSessions sessions, ConsoleUi ui, Path workspace, CliMode initialMode) {
        this.defaultMode = Objects.requireNonNull(initialMode);
        this.activeMode = initialMode;
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.ui = Objects.requireNonNull(ui, "ui must not be null");
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null")
                .toAbsolutePath()
                .normalize();
        this.intentRouter = new CliIntentRouter();
        this.workingContext = new CliWorkingContext();
        this.sessionStore = new CliSessionStore(this.workspace);
        this.conversationManager = new CliConversationManager(this.workspace);
        this.dirty = this.sessions.hasConversationHistory();
    }

    public void runInteractive() throws IOException {
        terminalInput = TerminalSessionInput.open(
                () -> "[" + (activeMode == CliMode.CODE ? "UNIFIED" : activeMode) + "] You > ",
                this::pickerConversations,
                () -> conversationManager.active() == null ? null : conversationManager.active().id(),
                id -> ui.withMessageRenderer(terminalInput::printAbove, () -> switchConversation(id)));
        if (terminalInput == null) {
            ui.printSystemMessage("Terminal key UI unavailable; use /sessions and /switch <id>.");
            run(new BufferedReader(new java.io.InputStreamReader(System.in)));
        } else {
            try (TerminalSessionInput input = terminalInput) { run(input.reader()); }
            finally { terminalInput = null; }
        }
    }

    private List<CliConversation> pickerConversations() {
        try { return conversationManager.list(); }
        catch (IOException | IllegalArgumentException failure) {
            ui.printSystemMessage("Session list unavailable; current session retained.");
            return List.of();
        }
    }

    public void run(BufferedReader reader) throws IOException {
        Objects.requireNonNull(reader, "reader must not be null");
        ui.printBanner(workspace, activeMode, sessions.code().planningMode());
        ui.printSystemMessage("Session: " + (conversationManager.active() == null ? "New session (starts with first task)"
                : SessionUiText.safe(conversationManager.active().title(), 40))
                + " | Global Memory: " + (crossState.enabled() ? "ON" : "OFF"));
        ui.printSystemMessage("Left on empty input: sessions | /help | /exit");
        int liveTestTurns = 0;
        while (true) {
            if (terminalInput == null) ui.promptUser(activeMode);
            String line;
            if (terminalInput != null) terminalInput.topLevel(true);
            try { line = readLine(reader); }
            finally { if (terminalInput != null) terminalInput.topLevel(false); }
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
                boolean completed = runAgent(block);
                if (LiveTestControl.enabled()) {
                    liveTestTurns++;
                    if (!continueLiveTestAfterTurn(completed, liveTestTurns)) return;
                }
                continue;
            }
            String input = line.trim();
            if (input.isEmpty()) {
                ui.finishEmptyInput();
                continue;
            }
            String command = input.toLowerCase(Locale.ROOT);
            if (command.equals("/verbose") || command.startsWith("/verbose ")) {
                String option = argumentAfterCommand(input, "/verbose").toLowerCase(Locale.ROOT);
                if (option.equals("on")) verbose = true;
                else if (option.equals("off")) verbose = false;
                else if (!option.equals("status") && !option.isBlank()) {
                    ui.printSystemMessage("Usage: /verbose on|off|status"); continue;
                }
                ui.printSystemMessage("Verbose: " + (verbose ? "ON" : "OFF") + "; /status shows latest task diagnostics.");
                continue;
            }
            if (command.equals("/status")) { printLastTaskDetails(); continue; }
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
            if (command.equals("/diff")) {
                ui.printDiff(lastTaskChanges);
                continue;
            }
            if (command.equals("/sessions")) {
                listConversations();
                continue;
            }
            if (command.equals("/new")) {
                newConversation();
                continue;
            }
            if (command.equals("/switch") || command.startsWith("/switch ")) {
                switchConversation(argumentAfterCommand(input, "/switch"));
                continue;
            }
            if (command.equals("/rename") || command.startsWith("/rename ")) {
                renameConversation(argumentAfterCommand(input, "/rename"));
                continue;
            }
            if (command.equals("/history")) {
                printHistory();
                continue;
            }
            if (command.equals("/memory") || command.startsWith("/memory ")) {
                memoryCommand(argumentAfterCommand(input, "/memory"));
                continue;
            }
            if (command.equals("/save") || command.startsWith("/save ")) {
                saveSession(argumentAfterCommand(input, "/save"));
                continue;
            }
            if (command.equals("/load") || command.startsWith("/load ")) {
                loadSession(reader, argumentAfterCommand(input, "/load"));
                continue;
            }
            if (command.equals("clear")) {
                lastResult = null;
                if (activeMode == CliMode.AUTO) {
                    sessions.clearAll();
                    crossState = crossState.withEnabled(crossState.enabled());
                    for (CliMode m : List.of(CliMode.CHAT, CliMode.READ, CliMode.CODE)) crossState = crossState.cleared(m);
                    lastAutoRoutedMode = null;
                    workingContext.clear();
                    lastTaskChanges = TaskChangeReview.empty("cleared");
                    ui.printSystemMessage("Legacy AUTO profile histories cleared.");
                } else {
                    sessions.session(activeMode).clearHistory();
                    crossState = crossState.cleared(activeMode);
                    lastTaskChanges = TaskChangeReview.empty("cleared");
                    ui.printSystemMessage(activeMode + " history cleared.");
                }
                dirty = true;
                checkpointConversation();
                continue;
            }
            boolean completed = runAgent(readPastedBlock(reader, line));
            if (LiveTestControl.enabled()) {
                liveTestTurns++;
                if (!continueLiveTestAfterTurn(completed, liveTestTurns)) return;
            }
        }
    }

    private boolean continueLiveTestAfterTurn(boolean completed, int turns) {
        if (!completed) return false;
        int turnLimit = liveTestTwoTurnModeEnabled() ? 2 : 1;
        if (turns >= turnLimit) return false;
        ui.printSystemMessage("LIVE_TEST_TURN_1_COMPLETE: one follow-up turn is allowed; request and time budgets remain global.");
        return true;
    }

    private static boolean liveTestTwoTurnModeEnabled() {
        if (!LiveTestControl.enabled()) return false;
        String value = System.getProperty("agent.live-test-two-turn-mode");
        if (value == null) value = System.getenv("AGENT_LIVE_TEST_TWO_TURN_MODE");
        return Boolean.parseBoolean(value);
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
        if (terminalInput != null) return firstLine; // JLine owns bracketed paste and line editing.

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

    private boolean runAgent(String task) {
        CliMode evidenceProfile = null;
        int exposureStart = -1;
        try {
            if (!prepareTaskSession(task)) return false;
            dirty = true;
            workingContext.observeUserTask(task);
            CliMode sessionMode = resolveSessionMode(task);
            configureMemoryForTurn(sessionMode, task);
            evidenceProfile = sessionMode;
            exposureStart = sessions.histories().get(sessionMode).size();
            if (activeMode == CliMode.AUTO) {
                ui.printSystemMessage("AUTO -> " + sessionMode);
            }
            String internalContext = workspaceContextForTask(task, sessionMode);
            String projectInstructions = sessionMode == CliMode.CODE
                    ? projectInstructionLoader.load(workspace, task, workingContext.lastResolvedFiles()).untrustedJson()
                    : "";
            AgentRunResult result = sessions.session(sessionMode).runWithTrajectory(
                    task, task, internalContext, projectInstructions);
            if (!LiveTestControl.enabled()) {
                sessionSummary = sessionSummarizer.recordTask(memoryScopeId, sessionMode, sessions.histories(), sessionSummary, result);
                lastMemoryRequests = sessions.session(sessionMode).sessionContextMetrics();
                lastMemoryUsage = result.usageSummary();
            }
            if (result.trajectory().completed() && crossState.enabled() && extraction.autoEnabled()) harvestMemory(false);
            lastTaskChanges = result.changeReview();
            lastResult = result;
            if (verbose) printLastTaskDetails();
            else if (!result.trajectory().completed()
                    || result.evidence().verificationStatus() == com.agent.agent.TaskEvidence.EvidenceStatus.FAIL
                    || result.evidence().verificationStatus() == com.agent.agent.TaskEvidence.EvidenceStatus.UNAVAILABLE
                    || result.evidence().functionalVerification() == com.agent.agent.TaskEvidence.EvidenceStatus.FAIL
                    || result.evidence().mutationStatus() == com.agent.agent.TaskEvidence.MutationStatus.FAILED)
                ui.printTaskEvidence(result.evidence());
            else if (result.evidence().functionalVerification() != com.agent.agent.TaskEvidence.EvidenceStatus.NOT_RUN)
                ui.printTaskEvidence(result.evidence());
            if (sessionMode == CliMode.CODE) ui.printTaskChanges(result.changeReview());
            if (lastMemoryRequests.stream().allMatch(m -> m.sharedMemories().isEmpty())) workingContext.observe(result.trajectory());
            if (result.trajectory().terminationReason() == TerminationReason.LLM_ERROR) {
                String failure = result.trajectory().steps().stream()
                        .filter(step -> step.actionType() == com.agent.agent.AgentActionType.ERROR)
                        .map(step -> step.errorMessage())
                        .filter(message -> message != null && !message.isBlank())
                        .reduce((first, second) -> second)
                        .orElse("LLM request failed");
                throw new IOException(failure);
            }
            if (result.trajectory().terminationReason() == TerminationReason.REQUEST_BUDGET_EXCEEDED) {
                ui.printSystemMessage("REQUEST_BUDGET_EXCEEDED: live-test request cap reached; stopping this task.");
                return false;
            }
            if (result.trajectory().terminationReason() == TerminationReason.TASK_TIMEOUT) {
                ui.printSystemMessage("TASK_TIMEOUT: live-test deadline reached; stopping this task.");
                return false;
            }
            if (result.trajectory().terminationReason() == TerminationReason.DIAGNOSTICS_FAILURE) {
                ui.printSystemMessage("DIAGNOSTICS_WRITE_FAILED: trace incomplete; stopping this task.");
                return false;
            }
            if (activeMode == CliMode.AUTO) {
                lastAutoRoutedMode = sessionMode;
            }
            return result.trajectory().completed();
        } catch (IOException exception) {
            ui.printSystemMessage("LLM streaming failed: " + safeMessage(exception));
            return false;
        } finally {
            if (evidenceProfile != null && !LiveTestControl.enabled()) {
                List<String> exposedIds = sessions.session(evidenceProfile).sessionContextMetrics().stream()
                        .flatMap(m -> m.sharedMemories().stream()).map(SharedMemoryHit.Trace::memoryId).distinct().toList();
                int end = sessions.histories().get(evidenceProfile).size();
                if (!exposedIds.isEmpty() && end > exposureStart) crossState = crossState.exposed(evidenceProfile, exposureStart, end, exposedIds);
            }
            checkpointConversation();
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

    private String workspaceContextForTask(String task, CliMode sessionMode) {
        if ((sessionMode != CliMode.READ && sessionMode != CliMode.CODE)
                || !workingContext.hasWorkspaceFacts()) {
            return "";
        }
        return workingContext.compactSnapshot();
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
                || command.equals("/status")
                || command.equals("/verbose") || command.startsWith("/verbose ")
                || command.equals("/diff")
                || command.equals("/sessions")
                || command.equals("/new")
                || command.equals("/switch") || command.startsWith("/switch ")
                || command.equals("/rename") || command.startsWith("/rename ")
                || command.equals("/history")
                || command.equals("/memory") || command.startsWith("/memory ")
                || command.equals("/save")
                || command.startsWith("/save ")
                || command.equals("/load")
                || command.startsWith("/load ")
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
        if (activeMode != mode || lastAutoRoutedMode != null) {
            dirty = true;
        }
        activeMode = mode;
        lastAutoRoutedMode = null;
        ui.printSystemMessage(mode == CliMode.CODE
                ? "Switched to UNIFIED mode (/code compatibility alias)."
                : "Switched to " + mode + " mode.");
        if (conversationManager.active() != null) checkpointConversation();
    }

    private void printHelp() {
        ui.printSystemMessage("Commands:");
        ui.printSystemMessage("Default   Unified agent; use tools as needed, edit only when requested");
        ui.printSystemMessage("First task creates a session and a local title; query commands do not create empty sessions.");
        ui.printSystemMessage("Empty input + Left: session picker; Up/Down select, Enter switch, Esc cancel. Otherwise use /sessions and /switch.");
        ui.printSystemMessage("/read     Strictly read/search the workspace; editing stays disabled");
        ui.printSystemMessage("/code     Compatibility alias for the unified agent");
        ui.printSystemMessage("/chat     Compatibility mode for general conversation without workspace tools");
        ui.printSystemMessage("/auto     Legacy per-task CHAT/READ/CODE routing with separate histories");
        ui.printSystemMessage("/help   Show commands");
        ui.printSystemMessage("/verbose on|off|status  Optional diagnostics (default OFF); /status shows the latest task summary");
        ui.printSystemMessage("/diff   Show the latest task's bounded file changes");
        ui.printSystemMessage("/begin  Start an explicit multiline message; finish it with /end");
        ui.printSystemMessage("/save <name>  Save all CLI profile histories");
        ui.printSystemMessage("/load <name>  Replace current CLI state from a saved session");
        ui.printSystemMessage("/sessions  List managed conversations (cross-session memory stays OFF)");
        ui.printSystemMessage("/new       Persist current conversation and start an independent UNIFIED session");
        ui.printSystemMessage("/switch <id>       Resume a managed conversation with its saved permissions");
        ui.printSystemMessage("/rename <id> <title>  Rename a managed conversation");
        ui.printSystemMessage("/history   Show recent messages from the current profile only");
        ui.printSystemMessage("/memory status|show|compact  Session-local extractive memory and ESTIMATED context budget");
        ui.printSystemMessage("/memory global on|off|status; /memory remember <text>  Explicit cross-session consent");
        ui.printSystemMessage("/memory list; /memory search <query>; /memory update <id> <text>; /memory forget <id>  Requires global ON");
        ui.printSystemMessage("/memory auto on|off|status; harvest; pending; approve <id>; reject <id>; extractor deterministic|llm (extra API cost)");
        ui.printSystemMessage("/memory retrieval keyword|semantic|hybrid|status");
        ui.printSystemMessage("/memory review <candidate>; replace <candidate> <target>; versions <memory>; conflict deterministic|llm|status");
        ui.printSystemMessage("/exit   Exit");
    }

    private void saveSession(String name) {
        if (name == null || name.isBlank()) {
            ui.printSystemMessage("Usage: /save <session-name>");
            return;
        }
        try {
            CliSessionSnapshot snapshot = currentSnapshot();
            sessionStore.save(name, snapshot);
            dirty = false;
            ui.printSystemMessage("Session saved: " + name);
        } catch (IOException | IllegalArgumentException exception) {
            ui.printSystemMessage("Session save failed: " + safeSessionError(exception));
        }
    }

    private void loadSession(BufferedReader reader, String name) throws IOException {
        if (name == null || name.isBlank()) {
            ui.printSystemMessage("Usage: /load <session-name>");
            return;
        }
        if (dirty) {
            ui.printSystemMessage("Unsaved session changes will be replaced. Continue? [y/N]");
            String confirmation = readLine(reader);
            if (confirmation == null || !confirmation.trim().equalsIgnoreCase("y")) {
                ui.printSystemMessage("Load cancelled; current session was kept.");
                return;
            }
        }

        try {
            CliSessionSnapshot snapshot = sessionStore.load(name);
            sessions.validateHistories(snapshot.histories());
            workingContext.validateSnapshot(snapshot.workingContext());

            if (conversationManager.active() != null && !checkpointConversation()) return;

            // Reads and validations complete before any active profile or CLI state is replaced.
            sessions.restoreHistories(snapshot.histories());
            workingContext.restore(snapshot.workingContext());
            activeMode = snapshot.activeMode();
            lastAutoRoutedMode = snapshot.lastAutoRoutedMode();
            lastTaskChanges = TaskChangeReview.empty("loaded-session");
            conversationManager.detach();
            memoryScopeId = java.util.UUID.randomUUID().toString();
            crossState = snapshot.crossSessionMemory();
        extraction = snapshot.extraction();
            sessionSummary = sessionSummarizer.summarize(memoryScopeId, sessions.histories(), SessionSummary.empty());
            resetMemoryRequestMetrics();
            dirty = false;
            ui.printSystemMessage("Session loaded: " + name);
            if (activeMode == CliMode.AUTO) {
                ui.printSystemMessage("This saved session retains legacy AUTO routing and separate profile histories. "
                        + "Use /code to enter the unified agent; histories are not merged.");
            }
        } catch (IOException | IllegalArgumentException exception) {
            ui.printSystemMessage("Session load failed: " + safeSessionError(exception));
        }
    }

    private CliSessionSnapshot currentSnapshot() {
        return new CliSessionSnapshot(CliSessionSnapshot.CURRENT_VERSION, Instant.now().toString(),
                activeMode, lastAutoRoutedMode, sessions.histories(), workingContext.snapshot(), sessionSummary, crossState, extraction);
    }

    private boolean checkpointConversation() { return checkpointConversation(false); }

    private boolean prepareTaskSession(String task) {
        if (LiveTestControl.enabled()) return true;
        // Match M1's failure behavior: keep usable in-memory history if storage is unavailable.
        if (conversationManager.active() == null && !checkpointConversation(true)) return true;
        var active = conversationManager.active();
        if (active != null && untitledSessions.remove(active.id()) && !sessions.hasConversationHistory()) {
            try { conversationManager.rename(active.id(), SessionUiText.title(task)); }
            catch (IOException | IllegalArgumentException failure) {
                ui.printSystemMessage("Automatic title unavailable; default title retained.");
            }
        }
        return true;
    }

    private boolean checkpointConversation(boolean force) {
        // Frozen, explicitly budgeted live experiments retain their existing persistence behavior.
        if (LiveTestControl.enabled()) return true;
        if (!force && conversationManager.active() == null && !sessions.hasConversationHistory()) return true;
        try {
            sessionSummary = sessionSummarizer.summarize(memoryScopeId, sessions.histories(), sessionSummary);
            CliSessionSnapshot snapshot = currentSnapshot();
            sessions.validateHistories(snapshot.histories());
            workingContext.validateSnapshot(snapshot.workingContext());
            boolean created = conversationManager.active() == null;
            if (created) {
                conversationManager.create(snapshot, memoryScopeId);
                if (!sessions.hasConversationHistory()) untitledSessions.add(memoryScopeId);
            }
            else conversationManager.checkpoint(snapshot);
            if (created) ui.printSystemMessage("Session created: " + conversationManager.active().id());
            return true;
        } catch (IOException | IllegalArgumentException exception) {
            localMemorySaveFailed=true;
            ui.printSystemMessage("Session not persisted; current history retained: " + safeSessionError(exception));
            return false;
        }
    }

    private boolean managedCommandsAvailable() {
        if (!LiveTestControl.enabled()) return true;
        ui.printSystemMessage("Managed conversation changes are unavailable during a controlled live test.");
        return false;
    }

    private void listConversations() {
        try {
            List<CliConversation> conversations = conversationManager.list();
            if (conversations.isEmpty()) ui.printSystemMessage("No managed sessions. Use /new or enter a task.");
            for (CliConversation conversation : conversations) {
                boolean current = conversationManager.active() != null
                        && conversationManager.active().id().equals(conversation.id());
                ui.printSystemMessage((current ? "* " : "  ") + conversation.id() + " | "
                        + SessionUiText.safe(conversation.title(), 120) + " | " + SessionUiText.localTime(conversation.updatedAt()));
            }
        } catch (IOException | IllegalArgumentException exception) {
            ui.printSystemMessage("Session list failed: " + safeSessionError(exception));
        }
    }

    private void newConversation() {
        if (!managedCommandsAvailable() || !checkpointConversation()) return;
        try {
            java.util.Map<CliMode, List<com.agent.llm.Message>> empty = new java.util.EnumMap<>(CliMode.class);
            sessions.histories().forEach((mode, messages) -> empty.put(mode, List.of(messages.get(0))));
            CliSessionSnapshot snapshot = new CliSessionSnapshot(1, Instant.now().toString(), defaultMode,
                    null, empty, new CliWorkingContext(workingContext.mode()).snapshot());
            sessions.validateHistories(snapshot.histories());
            CliConversation created = conversationManager.create(snapshot);
            restoreConversationSnapshot(snapshot);
            memoryScopeId = created.id();
            untitledSessions.add(created.id());
            ui.printSystemMessage("Session created: " + created.id() + " (" + (defaultMode == CliMode.CODE ? "UNIFIED" : defaultMode) + "; cross-session memory OFF)");
        } catch (IOException | IllegalArgumentException exception) {
            ui.printSystemMessage("New session failed; current history retained: " + safeSessionError(exception));
        }
    }

    private void switchConversation(String id) {
        if (!managedCommandsAvailable()) return;
        if (conversationManager.active() != null && conversationManager.active().id().equals(id)) {
            ui.printSystemMessage("Already in session: " + id + " | "
                    + SessionUiText.safe(conversationManager.active().title(), 40));
            printConversationPreview(conversationManager.active());
            return;
        }
        try {
            CliConversation candidate = conversationManager.load(id);
            sessions.validateHistories(candidate.snapshot().histories());
            workingContext.validateSnapshot(candidate.snapshot().workingContext());
            if (!checkpointConversation()) return;
            restoreConversationSnapshot(candidate.snapshot());
            conversationManager.activate(candidate);
            memoryScopeId = candidate.id();
            sessionSummary = sessionSummarizer.summarize(memoryScopeId, sessions.histories(), snapshotSummary(candidate));
            ui.printSystemMessage("Session switched: " + candidate.id() + " | " + SessionUiText.safe(candidate.title(), 40)
                    + " (permissions=" + activeMode + "; cross-session memory " + (crossState.enabled() ? "ON" : "OFF") + ")");
            printConversationPreview(candidate);
        } catch (IOException | IllegalArgumentException exception) {
            ui.printSystemMessage("Session switch failed; current history retained: " + safeSessionError(exception));
        }
    }

    private void printConversationPreview(CliConversation selected) {
        CliMode profile = activeMode == CliMode.AUTO
                ? (lastAutoRoutedMode == null ? CliMode.CODE : lastAutoRoutedMode) : activeMode;
        var messages = selected.snapshot().histories().get(profile).stream()
                .filter(m -> (m.role().equals("user") || m.role().equals("assistant"))
                        && m.toolCalls().isEmpty() && !m.content().isBlank()).toList();
        for (var message : messages.subList(Math.max(0, messages.size() - 4), messages.size()))
            ui.printSystemMessage("Preview " + message.role() + ": " + SessionUiText.safe(message.content(), 120));
    }

    private void restoreConversationSnapshot(CliSessionSnapshot snapshot) {
        sessions.restoreHistories(snapshot.histories());
        workingContext.restore(snapshot.workingContext());
        activeMode = snapshot.activeMode();
        lastAutoRoutedMode = snapshot.lastAutoRoutedMode();
        lastTaskChanges = TaskChangeReview.empty("switched-session");
        dirty = false;
        sessionSummary = snapshot.summary();
        crossState = snapshot.crossSessionMemory();
        extraction = snapshot.extraction();
        resetMemoryRequestMetrics();
    }

    private static SessionSummary snapshotSummary(CliConversation conversation) { return conversation.snapshot().summary(); }

    private void resetMemoryRequestMetrics() {
        lastResult = null;
        lastMemoryRequests = List.of();
        lastMemoryUsage = com.agent.llm.ModelRequestUsageSummary.empty();
        for (CliMode mode : List.of(CliMode.CHAT, CliMode.READ, CliMode.CODE)) sessions.session(mode).configureSessionMemory(null);
    }

    private void configureMemoryForTurn(CliMode profile, String task) throws IOException {
        try {
            SessionContextConfig config = SessionContextConfig.fromEnvironment();
            if (LiveTestControl.enabled()) {
                // Legacy live mode does not retrieve shared data. Imported exposure provenance
                // still enforces privacy, even while ordinary M2 compaction is disabled.
                sessions.session(profile).configureSessionMemory(crossState.exposures().isEmpty() ? null
                        : new SessionRequestContext(memoryScopeId, profile, sessionSummary,
                            new SessionContextConfig(false, config.budgetEstimatedTokens(), config.recentTurns()), crossState, List.of()));
                return;
            }
            sessionSummary = sessionSummarizer.summarize(memoryScopeId, sessions.histories(), sessionSummary);
            List<SharedMemoryHit> hits = List.of();
            if (crossState.enabled()) {
                try { hits = conversationManager.crossSessionMemory(() -> crossState.enabled(), crossState.retrievalMode()).retrieveAuthorized(memoryScopeId, task); }
                catch (IOException | IllegalArgumentException invalidStore) { ui.printSystemMessage("Cross-session memory unavailable; continuing without shared data."); }
            }
            sessions.session(profile).configureSessionMemory(new SessionRequestContext(memoryScopeId, profile, sessionSummary, config, crossState, hits));
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid session memory configuration: " + invalid.getMessage()); }
    }

    private void sharedMemoryCommand(String action) {
        if (!managedCommandsAvailable()) return;
        if (action.equals("list") || action.equals("search") || action.startsWith("search ")
                || action.equals("update") || action.startsWith("update ") || action.equals("forget") || action.startsWith("forget ")) {
            manageSharedMemory(action); return;
        }
        if (action.equals("global status")) {
            ui.printSystemMessage("Cross-session Memory=" + (crossState.enabled() ? "ON" : "OFF") + "; Session Memory remains independent.");
            printSharedMemoryCount(); return;
        }
        if (action.equals("global on") || action.equals("global off")) {
            boolean enable = action.endsWith("on");
            CrossSessionState previous = crossState;
            crossState = crossState.withEnabled(enable);
            resetMemoryRequestMetrics();
            if (!checkpointConversation(true) && enable) crossState = previous;
            ui.printSystemMessage("Cross-session Memory=" + (crossState.enabled() ? "ON" : "OFF")
                    + "; shared changes require explicit user memory commands.");
            return;
        }
        if (action.equals("remember") || action.startsWith("remember ")) {
            if (!crossState.enabled()) { ui.printSystemMessage("Cross-session Memory OFF: shared read/write denied."); return; }
            try {
                String text = action.length() > 8 ? action.substring(9) : "";
                LongTermMemory.validateContent(text);
                if (!checkpointConversation(true)) return;
                LongTermMemory saved = conversationManager.crossSessionMemory(() -> crossState.enabled(), crossState.retrievalMode()).rememberAuthorized(memoryScopeId, text);
                ui.printSystemMessage("Cross-session memory saved: " + saved.memoryId());
            } catch (SharedMemoryStore.DuplicateMemoryException duplicate) {
                ui.printSystemMessage("Memory already exists: " + duplicate.existingId() + "; no duplicate created.");
            } catch (IOException | IllegalArgumentException failure) {
                ui.printSystemMessage("Cross-session memory save failed; no memory accepted.");
            }
            return;
        }
        ui.printSystemMessage("Usage: /memory global on|off|status; /memory remember <text>");
    }

    private void printSharedMemoryCount() {
        if (!crossState.enabled()) { ui.printSystemMessage("Shared authorized entries=NOT_ACCESSED (global OFF)"); return; }
        try { ui.printSystemMessage("Shared authorized entries=" + conversationManager.crossSessionMemory(() -> crossState.enabled(), crossState.retrievalMode()).listAuthorized().size()); }
        catch (IOException | IllegalArgumentException unavailable) { ui.printSystemMessage("Shared authorized entries=UNKNOWN (library unavailable)"); }
    }

    private void manageSharedMemory(String action) {
        if (!crossState.enabled()) { ui.printSystemMessage("Cross-session Memory OFF: enable /memory global on before shared management."); return; }
        CrossSessionMemory memory = conversationManager.crossSessionMemory(() -> crossState.enabled(), crossState.retrievalMode());
        String[] parts = action.split("\\s+", 3);
        try {
            switch (parts[0]) {
                case "list" -> {
                    List<LongTermMemory> entries = memory.listAuthorized();
                    ui.printSystemMessage("Shared authorized entries=" + entries.size() + "; ordered by stable ID");
                    entries.forEach(m -> printSharedMemory(m, null));
                }
                case "search" -> {
                    String query = action.length() > 6 ? action.substring(7).trim() : "";
                    if (query.isEmpty() || query.length() > 1024) { ui.printSystemMessage("Search requires 1..1024 characters."); return; }
                    var hits = memory.retrieveAuthorized(memoryScopeId, query);
                    ui.printSystemMessage("Shared search: " + hits.size() + " hits (top " + LocalCrossSessionMemory.TOP_K + ")");
                    hits.forEach(hit -> printSharedMemory(hit.memory(), hit.score()));
                }
                case "update" -> {
                    if (parts.length != 3) { ui.printSystemMessage("Usage: /memory update <full-memory-id> <new-text>"); return; }
                    LongTermMemory updated = memory.updateAuthorized(parts[1], parts[2]);
                    resetMemoryRequestMetrics();
                    ui.printSystemMessage("Shared memory updated: " + updated.memoryId() + "; source and creation time retained; original history unchanged.");
                }
                case "forget" -> {
                    if (parts.length != 2) { ui.printSystemMessage("Usage: /memory forget <full-memory-id>"); return; }
                    memory.forgetAuthorized(parts[1]);
                    resetMemoryRequestMetrics();
                    ui.printSystemMessage("Shared memory deleted: " + parts[1] + "; removed from shared retrieval only. Original conversation history and prior files are not erased.");
                }
                default -> ui.printSystemMessage("Unknown shared memory management command.");
            }
        } catch (SharedMemoryStore.DuplicateMemoryException duplicate) {
            ui.printSystemMessage("Memory already exists: " + duplicate.existingId() + "; no entries changed.");
        } catch (java.util.NoSuchElementException absent) {
            ui.printSystemMessage("Memory id not found in this workspace; no entries changed.");
        } catch (IllegalArgumentException invalid) {
            ui.printSystemMessage("Invalid memory id or text; use full UUID and non-credential text of at most 1024 characters.");
        } catch (IOException failure) {
            ui.printSystemMessage("Shared memory operation failed; library was not overwritten and current history is retained.");
        }
    }

    private void printSharedMemory(LongTermMemory memory, Double score) {
        String summary = memory.content().replaceAll("[\\p{Cntrl}\\p{Cf}]", " ");
        if (summary.length() > 160) summary = summary.substring(0, 160) + " [summary truncated]";
        ui.printSystemMessage(memory.memoryId() + " | " + summary + " | source=" + memory.sourceSessionId()
                + " | updated=" + memory.updatedAt() + (score == null ? "" : " | relevance=" + String.format(Locale.ROOT, "%.3f", score)));
    }


    private void extractionCommand(String action) {
        if (!managedCommandsAvailable()) return;
        if (action.equals("auto status")) {
            ui.printSystemMessage("Auto extraction=" + extraction.autoEnabled() + ", extractor=" + (extraction.llmEnabled()?"LLM (extra cost)":"DETERMINISTIC")
                    + ", extra attempts=" + extraction.attempts() + ", separate provider usage=" + extraction.usage()); return;
        }
        if (action.equals("auto off")) { extraction=extraction.settings(false,extraction.llmEnabled()); checkpointConversation(true); return; }
        if (!crossState.enabled()) { ui.printSystemMessage("Global OFF: extraction and candidate access denied."); return; }
        if (action.equals("auto on") || action.equals("extractor deterministic") || action.equals("extractor llm")) {
            ExtractionState previous=extraction;
            extraction=extraction.settings(action.equals("auto on") || extraction.autoEnabled(), action.startsWith("extractor ")?action.endsWith("llm"):extraction.llmEnabled());
            if (!checkpointConversation(true)) extraction=previous;
            ui.printSystemMessage("Extractor settings saved; LLM mode incurs extra requests only on harvest/eligible turns."); return;
        }
        if (action.equals("harvest")) { harvestMemory(true); return; }
        if (action.equals("pending")) {
            extraction.candidates().stream().filter(c->c.reviewStatus().equals("PENDING")).forEach(c->ui.printSystemMessage(c.candidateId()+" | "+c.category()+" | "+c.candidateContent()+" | source="+c.sourceSessionId()+c.sourceMessageRefs()+" | quote="+c.evidenceQuote())); return;
        }
        String[] parts=action.split("\\s+",2);
        if(parts.length!=2) return;
        try {
            SharedMemoryStore.validateId(parts[1]);
            var candidate=extraction.candidates().stream().filter(c->c.candidateId().equals(parts[1]) && c.reviewStatus().equals("PENDING")).findFirst().orElseThrow();
            if(!candidate.sourceSessionId().equals(memoryScopeId)) throw new IllegalArgumentException();
            validateCandidateSource(candidate);
            if(parts[0].equals("approve")) {
                var relation=new DeterministicConflictDetector().detect(candidate.candidateContent(),conversationManager.crossSessionMemory(()->crossState.enabled()).listAuthorized());
                if(relation.relation()==ConflictDetector.Relation.POSSIBLE_CONFLICT){ui.printSystemMessage("Possible conflict: use /memory review then explicit /memory replace, or reject.");return;}

                try { conversationManager.crossSessionMemory(()->crossState.enabled()).rememberAuthorized(memoryScopeId,candidate.candidateContent()); }
                catch(SharedMemoryStore.DuplicateMemoryException duplicate) { /* Already authorized exact content; idempotent recovery. */ }
            }
            var next=extraction.candidates().stream().map(c->c.candidateId().equals(candidate.candidateId())?c.reviewed(parts[0].equals("approve")?"APPROVED":"REJECTED"):c).toList();
            extraction=new ExtractionState(extraction.autoEnabled(),extraction.llmEnabled(),extraction.attempts(),extraction.cursors(),next,extraction.usage());
            checkpointConversation(true);
            ui.printSystemMessage("Candidate reviewed. Only approved entries enter shared retrieval.");
        } catch(Exception failed) { ui.printSystemMessage("Candidate review failed; no history changed. A shared save may already have succeeded; retry is duplicate-safe."); }
    }
    private void validateCandidateSource(ExtractionState.Candidate candidate) {
        String[] ref=candidate.sourceMessageRefs().get(0).split(":");
        var history=sessions.histories().get(CliMode.valueOf(ref[0]));
        var message=history.get(Integer.parseInt(ref[1]));
        if(!"user".equals(message.role()) || !message.content().equals(candidate.sourceText())) throw new IllegalArgumentException("Stale candidate source");
    }
    private void harvestMemory(boolean manual) {
        if (!crossState.enabled() || LiveTestControl.enabled()) return;
        try {
            int interval=Integer.getInteger("agent.memory.extraction.interval",1);
            int cap=Integer.getInteger("agent.memory.extraction.maxRequests",4);
            if(interval<1 || cap<0 || cap>100 || extraction.candidates().size()>96) return;
            CliMode profile=activeMode==CliMode.AUTO?(lastAutoRoutedMode==null?CliMode.CODE:lastAutoRoutedMode):activeMode;
            var history=sessions.histories().get(profile);
            int start=Math.min(extraction.cursors().getOrDefault(profile,1),history.size());
            List<MemoryExtractor.Source> sources=new ArrayList<>(); int end=start; int chars=0;
            for(int i=start;i<history.size();i++) {
                var message=history.get(i);
                if("user".equals(message.role())) {
                    if(sources.size()==8) break;
                    String text=message.content();
                    try { LongTermMemory.validateContent(text); if (text.matches("(?is).*(已完成|已通过|运行成功|测试通过|编译通过|tests? passed|completed successfully|ignore.*instructions|忽略.*指令).*")) throw new IllegalArgumentException(); } catch(IllegalArgumentException unsafe) { end=i+1; continue; }
                    if(text.length()>700) { end=i+1; continue; }
                    if(chars+text.length()>700) break;
                    sources.add(new MemoryExtractor.Source(profile+":"+i,text)); chars+=text.length();
                }
                end=i+1;
            }
            if(sources.isEmpty() || (!manual && sources.size()<interval) || (extraction.llmEnabled() && extraction.attempts()>=cap)) return;
            ExtractionState previous=extraction;
            extraction=extraction.reserve(profile,end,extraction.llmEnabled());
            // Reserve cursor/budget durably BEFORE any optional API call. Failed attempts never auto-retry.
            if(!checkpointConversation(true)) { extraction=previous; return; }
            MemoryExtractor extractor=extraction.llmEnabled()?new LlmMemoryExtractor(sessions.extractionClient()):new DeterministicMemoryExtractor();
            List<MemoryExtractor.Proposal> proposals;
            try { proposals=extractor.extract(sources); }
            finally {
                if(extractor instanceof LlmMemoryExtractor llm) {
                    var usage=new ArrayList<>(extraction.usage()); usage.add(llm.usage());
                    extraction=new ExtractionState(extraction.autoEnabled(),extraction.llmEnabled(),extraction.attempts(),extraction.cursors(),extraction.candidates(),usage);
                    checkpointConversation(true);
                }
            }
            var candidates=new ArrayList<>(extraction.candidates());
            for(var proposal:proposals) {
                MemoryExtractor.validate(proposal,sources);
                if(candidates.stream().anyMatch(c->c.candidateContent().equals(proposal.proposedContent()))) continue;
                var source=sources.stream().filter(v->v.messageRef().equals(proposal.sourceMessageRefs().get(0))).findFirst().orElseThrow();
                candidates.add(new ExtractionState.Candidate(java.util.UUID.randomUUID().toString(),proposal.proposedContent(),proposal.category(),memoryScopeId,
                        proposal.sourceMessageRefs(),proposal.evidenceQuote(),source.userText(),Instant.now().toString(),"PENDING"));
            }
            extraction=new ExtractionState(extraction.autoEnabled(),extraction.llmEnabled(),extraction.attempts(),extraction.cursors(),candidates,extraction.usage());
            checkpointConversation(true);
            ui.printSystemMessage("Extraction finished: review with /memory pending; no shared write without approval.");
        } catch(Exception failure) { ui.printSystemMessage("Memory extraction skipped/failed; normal task result unchanged; no automatic retry."); }
    }


    private void conflictCommand(String action) {
        if(!managedCommandsAvailable())return;
        if(!crossState.enabled()){ui.printSystemMessage("Global OFF: review access denied.");return;}
        if(action.equals("conflict status")){ui.printSystemMessage("Conflict LLM="+llmConflictEnabled+"; extra calls="+conflictCalls+"; separate usage="+conflictUsage);return;}
        if(action.equals("conflict llm")||action.equals("conflict deterministic")){llmConflictEnabled=action.endsWith("llm");reviews.clear();ui.printSystemMessage("Conflict mode selected; LLM costs extra and only explicit review may call it.");return;}
        String[] args=action.split("\\s+");
        try {
            if(args[0].equals("versions")&&args.length==2){
                SharedMemoryStore.validateId(args[1]);
                var m=conversationManager.crossSessionMemory(()->crossState.enabled()).listAuthorized().stream().filter(v->v.memoryId().equals(args[1])).findFirst().orElseThrow();
                m.revisions().forEach(v->ui.printSystemMessage("OLD_VERSION (not current fact): "+v.oldContent()+" | candidate="+v.candidateId()+" | "+v.changedAt()+" | "+v.reason()));return;
            }
            if(args.length<2)throw new IllegalArgumentException();
            SharedMemoryStore.validateId(args[1]);
            var candidate=extraction.candidates().stream().filter(c->c.candidateId().equals(args[1])&&c.reviewStatus().equals("PENDING")).findFirst().orElseThrow();
            if(!candidate.sourceSessionId().equals(memoryScopeId))throw new IllegalArgumentException();validateCandidateSource(candidate);
            var authorized=conversationManager.crossSessionMemory(()->crossState.enabled()).listAuthorized();
            String fingerprint=SessionSummary.digest(authorized.stream().map(m->m.memoryId()+m.content()+m.updatedAt()).sorted().collect(java.util.stream.Collectors.joining()));
            String key=memoryScopeId+":"+candidate.candidateId();
            if(args[0].equals("review")&&args.length==2){
                Reviewed existing=reviews.get(key);
                if(existing==null||!existing.fingerprint().equals(fingerprint)){
                    var suggestion=new DeterministicConflictDetector().detect(candidate.candidateContent(),authorized);
                    if(llmConflictEnabled && suggestion.relation()!=ConflictDetector.Relation.DUPLICATE && conflictCalls<4){
                        conflictCalls++;var detector=new LlmConflictDetector(sessions.extractionClient());
                        String topic=candidate.candidateContent().toLowerCase(Locale.ROOT);
                        var nearby=authorized.stream().filter(m->m.content().equals(candidate.candidateContent())
                            || (topic.matches("(?s).*(java|jdk).*") && m.content().toLowerCase(Locale.ROOT).matches("(?s).*(java|jdk).*"))
                            || (topic.contains("maven") && m.content().toLowerCase(Locale.ROOT).contains("maven"))).limit(8).toList();
                        suggestion=detector.detect(candidate.candidateContent(),nearby);conflictUsage.add(detector.usage());
                    }
                    String target=suggestion.targetMemoryId();
                    String version=target==null?null:authorized.stream().filter(m->m.memoryId().equals(target)).map(m->SessionSummary.digest(m.content()+m.updatedAt())).findFirst().orElseThrow();
                    existing=new Reviewed(memoryScopeId,candidate.candidateId(),fingerprint,suggestion,version);reviews.put(key,existing);
                }
                ui.printSystemMessage("Candidate="+candidate.candidateContent()+" | source="+candidate.sourceMessageRefs()+" | quote="+candidate.evidenceQuote()+" | suggestion="+existing.suggestion().toString().replaceAll("[\\p{Cntrl}\\p{Cf}]", " "));return;
            }
            if(!args[0].equals("replace")||args.length!=3)throw new IllegalArgumentException();
            SharedMemoryStore.validateId(args[2]);Reviewed reviewed=reviews.get(key);
            if(reviewed==null||!reviewed.fingerprint().equals(fingerprint)||!args[2].equals(reviewed.suggestion().targetMemoryId()))throw new IllegalArgumentException("Stale/missing review");
            new SharedMemoryStore(workspace).updateChecked(args[2],workspace.toAbsolutePath().normalize().toString(),candidate.candidateContent(),reviewed.targetVersion(),candidate.candidateId());
            var next=extraction.candidates().stream().map(c->c.candidateId().equals(candidate.candidateId())?c.reviewed("APPROVED"):c).toList();
            extraction=new ExtractionState(extraction.autoEnabled(),extraction.llmEnabled(),extraction.attempts(),extraction.cursors(),next,extraction.usage());
            checkpointConversation(true);resetMemoryRequestMetrics();reviews.remove(key);
            ui.printSystemMessage("User-confirmed replacement saved; stable memory ID retained; /memory versions <id> shows provenance.");
        }catch(Exception failure){ui.printSystemMessage("Review/replacement unavailable or stale. Review again before exact-ID replacement; no history changed.");}
    }

    private void memoryCommand(String action) {
        if(action.startsWith("review ")||action.startsWith("replace ")||action.startsWith("versions ")||action.startsWith("conflict ")){conflictCommand(action);return;}

        if(action.startsWith("retrieval ")) {
            if(!managedCommandsAvailable())return;
            String mode=action.substring(10).trim();
            if(mode.equals("status")){ui.printSystemMessage("Retrieval="+crossState.retrievalMode()+"; embedding="+conversationManager.embeddingMetrics());return;}
            if(!crossState.enabled()){ui.printSystemMessage("Global OFF: retrieval configuration denied.");return;}
            if(!List.of("keyword","semantic","hybrid").contains(mode)){ui.printSystemMessage("Use keyword|semantic|hybrid|status");return;}
            var previous=crossState;crossState=new CrossSessionState(crossState.enabled(),crossState.exposures(),mode);
            if(!checkpointConversation(true))crossState=previous;
            resetMemoryRequestMetrics();ui.printSystemMessage("Retrieval="+crossState.retrievalMode()+"; unconfigured embedding falls back to keyword.");return;
        }

        if (action.startsWith("auto ") || action.startsWith("extractor ") || action.equals("harvest") || action.equals("pending") || action.startsWith("approve ") || action.startsWith("reject ")) {
            extractionCommand(action); return;
        }
        if (action.startsWith("global ") || action.equals("remember") || action.startsWith("remember ")
                || action.equals("list") || action.equals("search") || action.startsWith("search ")
                || action.equals("update") || action.startsWith("update ") || action.equals("forget") || action.startsWith("forget ")) {
            sharedMemoryCommand(action); return;
        }
        if (!List.of("status", "show", "compact").contains(action.toLowerCase(Locale.ROOT))) {
            ui.printSystemMessage("Usage: /memory status|show|compact; list|search <query>|update <id> <text>|forget <id> require global ON"); return;
        }
        if (LiveTestControl.enabled()) {
            ui.printSystemMessage("Session memory compaction is disabled in controlled live-test mode; cross-session memory OFF."); return;
        }
        try {
            SessionContextConfig config = SessionContextConfig.fromEnvironment();
            sessionSummary = sessionSummarizer.summarize(memoryScopeId, sessions.histories(), sessionSummary);
            if (action.equalsIgnoreCase("compact")) {
                sessionSummary = sessionSummary.compactManually(sessions.histories());
                checkpointConversation();
                ui.printSystemMessage("Session context compaction requested; full storage history retained; zero summarizer API requests.");
            }
            if (action.equalsIgnoreCase("show")) {
                ui.printSystemMessage("Session memory " + memoryScopeId + ": quoted user sources and historical runtime events; use /memory global status for shared consent.");
                for (SessionSummary.Fact fact : sessionSummary.facts()) {
                    ui.printSystemMessage(fact.kind() + " [" + fact.profile() + " message=" + fact.messageIndex()
                            + " sha256=" + fact.sourceSha256() + "] " + fact.text().replaceAll("[\\p{Cntrl}]", " "));
                }
                for (SessionSummary.TaskState task : sessionSummary.tasks()) {
                    ui.printSystemMessage("TASK_STATE [" + task.profile() + " userMessage=" + task.userMessageIndex() + " run=" + task.runId()
                            + "] execution=" + task.execution() + ", completed=" + task.completed() + ", mutation=" + task.mutation()
                            + ", verification=" + task.verification() + ", functional=" + task.functional()
                            + "; changedPaths=" + task.changedPaths().toString().replaceAll("[\\p{Cntrl}]", " ")
                            + "; evidence=" + task.evidenceSources().toString().replaceAll("[\\p{Cntrl}]", " "));
                }
                return;
            }
            CliMode profile = activeMode == CliMode.AUTO ? (lastAutoRoutedMode == null ? CliMode.CODE : lastAutoRoutedMode) : activeMode;
            int total = sessions.histories().values().stream().mapToInt(List::size).sum();
            printSharedMemoryCount();
            ui.printSystemMessage("Memory scope=" + memoryScopeId + ", enabled=" + config.enabled() + ", cross-session=" + (crossState.enabled() ? "ON" : "OFF") + ", storageMessagesAllProfiles="
                    + total + ", recentTurns=" + config.recentTurns() + ", manualCompact=" + sessionSummary.manualCompact());
            var preview = sessions.session(profile).previewSessionMemory(new SessionRequestContext(memoryScopeId, profile, sessionSummary, config, crossState, List.of()));
            printMemoryMetrics(preview.metrics(), "preview, not an HTTP request");
            for (int index = 0; index < lastMemoryRequests.size(); index++) printMemoryMetrics(lastMemoryRequests.get(index), "last task decision " + (index + 1));
            for (var usage : lastMemoryUsage.requests()) {
                ui.printSystemMessage("PROVIDER_REPORTED request=" + usage.requestSequence() + ", status=" + usage.status()
                        + ", input=" + tokenValue(usage.inputTokens()) + ", output=" + tokenValue(usage.outputTokens())
                        + ", total=" + tokenValue(usage.totalTokens()) + ", cacheHit=" + tokenValue(usage.cacheHitTokens()));
            }
            if (lastMemoryUsage.requests().isEmpty()) ui.printSystemMessage("PROVIDER_REPORTED usage=UNKNOWN (no provider usage record in this CLI task)");
        } catch (IllegalArgumentException invalid) { ui.printSystemMessage("Memory configuration invalid: " + invalid.getMessage()); }
    }

    private static String tokenValue(Integer value) { return value == null ? "UNKNOWN" : value.toString(); }

    private void printMemoryMetrics(SessionContextMetrics metrics, String label) {
        if (!metrics.sharedMemories().isEmpty()) ui.printSystemMessage("Cross-session selected (ESTIMATED; IDs/scores only): " + metrics.sharedMemories());

        ui.printSystemMessage("Memory context (" + label + "): storedProfileMessages=" + metrics.fullHistoryMessages()
                + ", selectedHistoryMessages=" + metrics.selectedHistoryMessages() + ", ESTIMATED_UTF8_BYTES_DIV_4="
                + metrics.selectedEstimatedTokens() + "/" + metrics.budgetEstimatedTokens() + ", fullEstimate=" + metrics.fullEstimatedTokens()
                + ", estimatedReduction=" + String.format(Locale.ROOT, "%.1f%%", metrics.estimatedSavingRatio() * 100)
                + ", compacted=" + metrics.compacted() + ", injected=" + metrics.injectedKinds() + ", " + metrics.reason());
    }

    private void printLastTaskDetails() {
        if (lastResult == null) { ui.printSystemMessage("No task diagnostics in this active session yet."); return; }
        ui.printUsageSummary(lastResult.usageSummary());
        ui.printTaskEvidence(lastResult.evidence());
        if (!lastMemoryRequests.isEmpty() && !LiveTestControl.enabled())
            printMemoryMetrics(lastMemoryRequests.get(lastMemoryRequests.size() - 1), "last request");
        if (verbose) ui.printSystemMessage("Planning: " + sessions.code().planningMode());
    }

    private void renameConversation(String arguments) {
        if (!managedCommandsAvailable()) return;
        String[] parts = arguments.split("\\s+", 2);
        if (parts.length != 2) { ui.printSystemMessage("Usage: /rename <session-id> <title>"); return; }
        try {
            if (conversationManager.active() != null && conversationManager.active().id().equals(parts[0])
                    && !checkpointConversation()) return;
            conversationManager.rename(parts[0], parts[1]);
            untitledSessions.remove(parts[0]);
            ui.printSystemMessage("Session renamed: " + parts[0]);
        } catch (IOException | IllegalArgumentException exception) {
            ui.printSystemMessage("Session rename failed: " + safeSessionError(exception));
        }
    }

    private void printHistory() {
        CliMode mode = activeMode == CliMode.AUTO
                ? (lastAutoRoutedMode == null ? CliMode.CODE : lastAutoRoutedMode) : activeMode;
        List<com.agent.llm.Message> history = sessions.session(mode).history().stream()
                .filter(message -> !"system".equals(message.role())).toList();
        ui.printSystemMessage("Current session history (" + mode + ", latest 100 messages; display capped per message):");
        for (com.agent.llm.Message message : history.subList(Math.max(0, history.size() - 100), history.size())) {
            String text = "tool".equals(message.role()) ? "result for " + message.toolCallId()
                    : message.content().replaceAll("[\\p{Cntrl}]", " ");
            if (text.length() > 500) text = text.substring(0, 500) + " [display truncated]";
            ui.printSystemMessage(message.role() + ": " + text
                    + (message.toolCalls().isEmpty() ? "" : " [structured tool calls=" + message.toolCalls().size() + "]"));
        }
    }

    private static String argumentAfterCommand(String input, String command) {
        return input.length() <= command.length() ? "" : input.substring(command.length()).trim();
    }

    private static String safeSessionError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.replaceAll("(?i)Bearer\\s+\\S+", "Bearer [REDACTED]");
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
