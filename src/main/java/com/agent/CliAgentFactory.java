package com.agent;

import com.agent.agent.Agent;
import com.agent.agent.AgentEventListener;
import com.agent.agent.TaskMode;
import com.agent.llm.GlmClient;
import com.agent.llm.LLMClient;
import com.agent.tool.ToolRegistry;
import com.agent.tool.execution.DefaultProcessRunner;

import java.nio.file.Path;
import java.util.Objects;

/** Builds the interactive CLI with the frozen V1 Diagnostic Recovery behavior. */
public final class CliAgentFactory {
    private static final String CHAT_SYSTEM_PROMPT = """
            You are a helpful general assistant. Answer general knowledge, programming language,
            and algorithm questions directly. You cannot access the user's workspace in this mode.
            If the user asks you to inspect or modify workspace files, explain that CHAT mode cannot
            do so and ask them to switch to /read or /code. Never claim that you read or changed a
            workspace file.
            """;
    private static final String READ_SYSTEM_PROMPT = """
            You are a read-only repository assistant. Use the available tools to inspect, read, and
            search the current workspace when needed. You cannot modify files or run tests in this
            mode. If the user requests a modification, explain that READ mode is read-only and ask
            them to switch to /code. Never claim that a file was changed.
            """;
    private static final String V1_SYSTEM_PROMPT = """
            You are a Coding Agent, not a coding advisor. When a task asks you to fix, change,
            repair, refactor, or implement code, modify the workspace with the provided tools.
            Diagnosis, a suggested patch, or a code snippet is not task completion.
            When the user names an exact workspace-relative file path, read and edit that exact file.
            Do not substitute a different same-named file found elsewhere in the workspace.

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

    private CliAgentFactory() {
    }

    public static CliSessions createProfiles(Path workspace, AgentEventListener eventListener) {
        LLMClient client = new GlmClient();
        return createProfiles(client, workspace, eventListener);
    }

    static CliSessions createProfiles(
            LLMClient client,
            Path workspace,
            AgentEventListener eventListener
    ) {
        Objects.requireNonNull(client, "client must not be null");
        return new CliSessions(
                createChat(client, eventListener),
                createReadOnly(client, workspace, eventListener),
                createCoding(client, workspace, eventListener)
        );
    }

    public static Agent createChat(LLMClient client, AgentEventListener eventListener) {
        return new Agent(
                Objects.requireNonNull(client, "client must not be null"),
                new ToolRegistry(),
                CHAT_SYSTEM_PROMPT,
                Agent.MAX_ITERATIONS,
                TaskMode.READ_ONLY,
                false,
                false,
                Objects.requireNonNull(eventListener, "eventListener must not be null")
        );
    }

    public static Agent createReadOnly(
            LLMClient client,
            Path workspace,
            AgentEventListener eventListener
    ) {
        return new Agent(
                Objects.requireNonNull(client, "client must not be null"),
                ToolRegistry.withFileTools(normalize(workspace)),
                READ_SYSTEM_PROMPT,
                Agent.MAX_ITERATIONS,
                TaskMode.READ_ONLY,
                false,
                false,
                Objects.requireNonNull(eventListener, "eventListener must not be null")
        );
    }

    public static Agent createCoding(
            LLMClient client,
            Path workspace,
            AgentEventListener eventListener
    ) {
        Path normalizedWorkspace = normalize(workspace);
        ToolRegistry registry = ToolRegistry.withActionOrientedCodingTools(
                normalizedWorkspace,
                new DefaultProcessRunner(),
                normalizedWorkspace.resolve(".m2/repository")
        );
        return new Agent(
                Objects.requireNonNull(client, "client must not be null"),
                registry,
                V1_SYSTEM_PROMPT,
                Agent.MAX_ITERATIONS,
                TaskMode.CODE_MODIFICATION,
                true,
                false,
                Objects.requireNonNull(eventListener, "eventListener must not be null")
        );
    }

    private static Path normalize(Path workspace) {
        return Objects.requireNonNull(workspace, "workspace must not be null")
                .toAbsolutePath()
                .normalize();
    }
}
