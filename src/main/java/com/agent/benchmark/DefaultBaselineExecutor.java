package com.agent.benchmark;

import com.agent.agent.Agent;
import com.agent.agent.AgentEventListener;
import com.agent.agent.AgentRunResult;
import com.agent.agent.TaskMode;
import com.agent.environment.LocalWorkspaceEnvironment;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolDefinition;
import com.agent.tool.ToolRegistry;
import com.agent.tool.execution.DefaultProcessRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class DefaultBaselineExecutor implements BenchmarkAgentExecutor {
    private static final String SYSTEM_PROMPT =
            "You are a coding assistant. Inspect, patch, and test the workspace when tools are available.";
    private static final String ACTION_ORIENTED_SYSTEM_PROMPT = """
            You are a Coding Agent, not a coding advisor. When a task asks you to fix, change,
            repair, refactor, or implement code, modify the workspace with the provided tools.
            Diagnosis, a suggested patch, or a code snippet is not task completion.

            For a code-modification task, inspect the relevant code, make the required change with
            apply_patch, and validate the workspace. When run_maven_test is available and applicable,
            use it after editing. If a test fails, inspect the observation, continue working, and
            retest. Only then return a final answer. You may choose the useful tool order and may
            inspect, patch, or test more than once.

            If a tool reports invalid arguments, read the error, correct the arguments, and retry or
            choose another tool. An empty search result does not prove that the code is absent; try
            list_files, read_file, another query, or another directory. After TEXT_NOT_FOUND or
            MULTIPLE_MATCHES, reread the latest file and construct a more precise patch. Do not repeat
            an identical failed action without changing the strategy.
            """;
    private static final String DIAGNOSTIC_RECOVERY_SYSTEM_PROMPT = ACTION_ORIENTED_SYSTEM_PROMPT + """

            After run_maven_test fails, read the key diagnostic carefully and identify the exact
            file, line, symbol, syntax error, or assertion involved. Re-read the current version of
            a modified source file before another patch. Make the smallest targeted repair based on
            the actual current source, then rerun the narrowest relevant test when possible.

            For cannot-find-symbol diagnostics, first check missing imports, misspelled identifiers,
            and unavailable classes or methods. For syntax diagnostics, reread the file and inspect
            braces, parentheses, duplicated blocks, and malformed replacements. For assertion
            failures, decide from the task, production source, and test whether the implementation or
            expectation is wrong. Do not blindly trust the task category. Avoid unrelated null logic,
            refactors, or structural changes unless the task or diagnostic requires them.
            """;
    private static final String PLANNING_SYSTEM_PROMPT = DIAGNOSTIC_RECOVERY_SYSTEM_PROMPT + """

            Before editing a codebase, identify every concrete behavioral or structural requirement
            in the task and maintain a short public execution plan. Do not provide private reasoning.
            Do not treat fixing one issue or passing one visible test as proof that all requirements
            are complete. Before finalizing, review every remaining requirement.

            Record the plan inside the same assistant decision by including this compact structure;
            it may accompany tool calls and does not require a separate planning response:
            <plan_update>{"goal":"short goal","requirements":[
            {"id":"R1","description":"one requirement","status":"PENDING","evidence":""}],
            "currentFocus":"R1","notes":""}</plan_update>

            Keep stable R1/R2 identifiers and 2-8 one-sentence requirements. Include inspection,
            behavioral changes, task-explicit structure, and validation only when they are real task
            requirements. Update the complete plan snapshot as observations arrive. Mark a requirement
            COMPLETED only with concise observable evidence; a test pass never completes unrelated
            requirements automatically. Status values are PENDING, IN_PROGRESS, COMPLETED, or BLOCKED.
            You may add a newly discovered requirement without fragmenting the plan indefinitely.
            """;
    private static final String PRECISE_EDIT_SYSTEM_PROMPT = DIAGNOSTIC_RECOVERY_SYSTEM_PROMPT + """

            Base every edit on the latest current source. For a clear local change, prefer
            read_file with includeLineNumbers=true followed by replace_lines using the exact current
            expectedText and a small inclusive range. Use apply_patch when a unique exact oldText is
            short and reliable. If apply_patch returns TEXT_NOT_FOUND or MULTIPLE_MATCHES, do not keep
            guessing oldText: reread current line-numbered source and fall back to replace_lines.
            If replace_lines returns STALE_EDIT_CONTEXT, reread and construct a fresh range edit rather
            than repeating stale arguments. Preserve surrounding structure and validate after editing.
            """;

    private final Supplier<LLMClient> clientSupplier;
    private final Path localRepository;

    public DefaultBaselineExecutor(Supplier<LLMClient> clientSupplier, Path localRepository) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier);
        this.localRepository = Objects.requireNonNull(localRepository).toAbsolutePath().normalize();
    }

    @Override
    public AgentRunResult run(BenchmarkTask task, Path workspace, BaselineType baseline) {
        LLMClient client = clientSupplier.get();
        LLMClient policy = switch (baseline) {
            case SINGLE_SHOT_NO_TOOL -> noToolPolicy(client);
            case SINGLE_TOOL_ROUND -> singleToolRoundPolicy(client);
            case REACT, REACT_ACTION_ORIENTED, REACT_DIAGNOSTIC_RECOVERY,
                    REACT_PLANNING, REACT_PRECISE_EDIT -> client;
        };
        ToolRegistry registry = switch (baseline) {
            case SINGLE_SHOT_NO_TOOL -> new ToolRegistry();
            case REACT_ACTION_ORIENTED, REACT_DIAGNOSTIC_RECOVERY, REACT_PLANNING ->
                    ToolRegistry.withActionOrientedCodingTools(
                    workspace,
                    new DefaultProcessRunner(),
                    localRepository
            );
            case REACT_PRECISE_EDIT -> ToolRegistry.withPreciseEditCodingTools(
                    workspace,
                    new DefaultProcessRunner(),
                    localRepository
            );
            case SINGLE_TOOL_ROUND, REACT -> ToolRegistry.withCodingTools(
                    workspace,
                    new DefaultProcessRunner(),
                    localRepository
            );
        };
        boolean actionOriented = baseline == BaselineType.REACT_ACTION_ORIENTED
                || baseline == BaselineType.REACT_DIAGNOSTIC_RECOVERY
                || baseline == BaselineType.REACT_PLANNING
                || baseline == BaselineType.REACT_PRECISE_EDIT;
        boolean diagnosticRecovery = baseline == BaselineType.REACT_DIAGNOSTIC_RECOVERY
                || baseline == BaselineType.REACT_PLANNING
                || baseline == BaselineType.REACT_PRECISE_EDIT;
        boolean planning = baseline == BaselineType.REACT_PLANNING;
        return new Agent(
                policy,
                new LocalWorkspaceEnvironment(workspace, registry),
                diagnosticRecovery
                        ? baseline == BaselineType.REACT_PRECISE_EDIT
                        ? PRECISE_EDIT_SYSTEM_PROMPT
                        : DIAGNOSTIC_RECOVERY_SYSTEM_PROMPT
                        : actionOriented ? ACTION_ORIENTED_SYSTEM_PROMPT : SYSTEM_PROMPT,
                task.maxSteps(),
                actionOriented && task.requiresModification()
                        ? TaskMode.CODE_MODIFICATION
                        : TaskMode.READ_ONLY,
                diagnosticRecovery,
                planning,
                AgentEventListener.NO_OP,
                false
        )
                .runWithTrajectory(task.description());
    }

    private static LLMClient noToolPolicy(LLMClient delegate) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(List<Message> messages) throws IOException {
                return stripToolCalls(delegate.chat(messages, List.of()));
            }

            @Override
            public LLMResponse chat(
                    List<Message> messages,
                    List<ToolDefinition> ignored
            ) throws IOException {
                return stripToolCalls(delegate.chat(messages, List.of()));
            }
        };
    }

    private static LLMClient singleToolRoundPolicy(LLMClient delegate) {
        AtomicBoolean firstDecision = new AtomicBoolean(true);
        return new LLMClient() {
            @Override
            public LLMResponse chat(List<Message> messages) throws IOException {
                return chat(messages, List.of());
            }

            @Override
            public LLMResponse chat(
                    List<Message> messages,
                    List<ToolDefinition> tools
            ) throws IOException {
                if (firstDecision.getAndSet(false)) {
                    LLMResponse response = delegate.chat(messages, tools);
                    if (response.toolCalls().size() <= 1) {
                        return response;
                    }
                    return new LLMResponse(
                            response.content(),
                            List.of(response.toolCalls().get(0))
                    );
                }
                return stripToolCalls(delegate.chat(messages, List.of()));
            }
        };
    }

    private static LLMResponse stripToolCalls(LLMResponse response) {
        return new LLMResponse(response.content(), List.of());
    }
}
