package com.agent;

import com.agent.agent.Agent;
import com.agent.agent.AgentEventListener;
import com.agent.agent.TaskMode;
import com.agent.agent.PlanningMode;
import com.agent.agent.VerificationRepairPolicy;
import com.agent.environment.LocalWorkspaceEnvironment;
import com.agent.environment.AgentEnvironment;
import com.agent.llm.LLMClient;
import com.agent.llm.LlmClientFactory;
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
            For a workspace-root listing, call list_files with "."; do not pass an empty path.
            Use find_files when the exact file path is unknown and the user refers to files by extension,
            filename pattern, or category. Simple filename patterns such as *.py or *Agent*.java search
            recursively across the workspace. Use directory-qualified patterns only to restrict a search,
            for example src/**/*.java. Use search_code only to search text inside files.
            """;
    private static final String V1_SYSTEM_PROMPT = """
            You are a Coding Agent, not a coding advisor. When a task asks you to fix, change,
            repair, refactor, or implement code, modify the workspace with the provided tools.
            Diagnosis, a suggested patch, or a code snippet is not task completion.
            When the user names an exact workspace-relative file path, read and edit that exact file.
            Do not substitute a different same-named file found elsewhere in the workspace.
            For a workspace-root listing, call list_files with "."; do not pass an empty path.
            Use find_files when the exact file path is unknown and the user refers to files by extension,
            filename pattern, or category. Simple filename patterns such as *.py or *Agent*.java search
            recursively across the workspace. Use directory-qualified patterns only to restrict a search,
            for example src/**/*.java. Use search_code only to search text inside files.
            Use apply_patch for existing files. Use create_file only for a genuinely new file; it never
            overwrites an existing path.
            Use apply_patch for replacing existing exact text. Use insert_before when new content naturally belongs
            before a unique existing anchor, and use insert_after when it belongs after one. For example, add a
            top-level C/C++ helper before main with insert_before(anchor="int main() {"). Add a helper after an
            import/include section with insert_after and a stable include/import anchor. Do not choose an unnatural
            anchor merely to use insert_after. Read the file first and choose a short, unique, stable anchor. If
            the user specifies an existing target file and editing fails, do not create a
            substitute file; reread and recover on that same target.
            When using insert_after, choose a complete, standalone, structurally stable anchor. Good anchors
            include a complete import/include line, "using namespace std;", a complete statement, or a complete
            method/function block. Bad anchors include partial signatures such as "int main", partial expressions,
            fragments inside another function body, or text that would place a top-level function inside another
            function. For a new C/C++ top-level helper, prefer a stable pre-main anchor when the latest read shows
            one. For a Java method, use a complete class-internal anchor; never insert outside the class or inside
            another method. Never use an empty oldText. If create_file reports FILE_ALREADY_EXISTS, read that file
            and use apply_patch if a change is still needed; do not retry create_file for the same path.
            After a successful write, read the exact changed file again before another write or final
            answer. Confirm both the requested change and preservation of existing content.
            After a successful mutation, reread the modified file as required. If the requested change is
            present after rereading and no required verification has failed, finish the task. Do not perform
            additional cleanup, rewriting, refactoring, or cosmetic edits unless requested or verification
            shows the change is incorrect.
            Base any subsequent edit on the latest reread, never on stale pre-edit content.
            Never claim that tests passed unless run_maven_test actually succeeded after the latest
            workspace change. For Java source or test changes, run Maven tests when the tool is available.

            For a code-modification task, inspect the relevant code, make the required change with
            apply_patch, and validate the workspace. When run_maven_test is available and applicable,
            use it after editing. If a test fails, inspect the observation, continue working, and
            retest. Only then return a final answer. You may choose the useful tool order and may
            inspect, patch, or test more than once.

            If a tool reports invalid arguments, read the error, correct the arguments, and retry or
            choose another tool. An empty search result does not prove that the code is absent; try
            list_files, read_file, another query, or another directory. After TEXT_NOT_FOUND or
            MULTIPLE_MATCHES, reread the latest file and construct a more precise patch. Do not repeat
            an identical failed action without changing the strategy. After NO_EFFECT_CHANGE, inspect
            the current file and task again instead of resubmitting the same patch.

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
        LLMClient client = LlmClientFactory.create();
        return createProfiles(client, workspace, eventListener, PlanningMode.fromEnvironment());
    }

    static CliSessions createProfiles(
            LLMClient client,
            Path workspace,
            AgentEventListener eventListener
    ) {
        return createProfiles(client, workspace, eventListener, PlanningMode.REACTIVE);
    }

    static CliSessions createProfiles(
            LLMClient client,
            Path workspace,
            AgentEventListener eventListener,
            PlanningMode planningMode
    ) {
        Objects.requireNonNull(client, "client must not be null");
        return new CliSessions(
                createChat(client, workspace, eventListener),
                createReadOnly(client, workspace, eventListener),
                createCoding(client, workspace, eventListener, planningMode)
        );
    }

    public static Agent createChat(LLMClient client, AgentEventListener eventListener) {
        return createChat(client, Path.of("."), eventListener);
    }

    public static Agent createChat(
            LLMClient client,
            Path workspace,
            AgentEventListener eventListener
    ) {
        return new Agent(
                Objects.requireNonNull(client, "client must not be null"),
                new LocalWorkspaceEnvironment(normalize(workspace), new ToolRegistry()),
                CHAT_SYSTEM_PROMPT,
                Agent.MAX_ITERATIONS,
                TaskMode.READ_ONLY,
                false,
                false,
                Objects.requireNonNull(eventListener, "eventListener must not be null"),
                true
        );
    }

    public static Agent createReadOnly(
            LLMClient client,
            Path workspace,
            AgentEventListener eventListener
    ) {
        ToolRegistry registry = ToolRegistry.withFileTools(normalize(workspace));
        return new Agent(
                Objects.requireNonNull(client, "client must not be null"),
                new LocalWorkspaceEnvironment(normalize(workspace), registry),
                READ_SYSTEM_PROMPT,
                Agent.MAX_ITERATIONS,
                TaskMode.READ_ONLY,
                false,
                false,
                Objects.requireNonNull(eventListener, "eventListener must not be null"),
                true
        );
    }

    public static Agent createCoding(
            LLMClient client,
            Path workspace,
            AgentEventListener eventListener
    ) {
        Path normalizedWorkspace = normalize(workspace);
        return createCoding(client, normalizedWorkspace, eventListener,
                normalizedWorkspace.resolve(".m2/repository"), PlanningMode.REACTIVE);
    }

    public static Agent createCoding(
            LLMClient client,
            Path workspace,
            AgentEventListener eventListener,
            PlanningMode planningMode
    ) {
        Path normalizedWorkspace = normalize(workspace);
        return createCoding(client, normalizedWorkspace, eventListener,
                normalizedWorkspace.resolve(".m2/repository"), planningMode);
    }

    /** Creates the same CODE profile with an explicit Maven cache for isolated benchmark workspaces. */
    public static Agent createCoding(
            LLMClient client,
            Path workspace,
            AgentEventListener eventListener,
            Path localRepository
    ) {
        return createCoding(client, workspace, eventListener, localRepository, PlanningMode.REACTIVE);
    }

    public static Agent createCoding(
            LLMClient client,
            Path workspace,
            AgentEventListener eventListener,
            Path localRepository,
            PlanningMode planningMode
    ) {
        Path normalizedWorkspace = normalize(workspace);
        ToolRegistry registry = ToolRegistry.withCliCodingTools(
                normalizedWorkspace,
                new DefaultProcessRunner(),
                Objects.requireNonNull(localRepository,"localRepository must not be null").toAbsolutePath().normalize()
        );
        return createCoding(client, new LocalWorkspaceEnvironment(normalizedWorkspace, registry),
                eventListener, planningMode);
    }

    /** Creates the production CODE profile with a caller-supplied environment (for isolated harnesses). */
    public static Agent createCoding(
            LLMClient client,
            AgentEnvironment environment,
            AgentEventListener eventListener,
            PlanningMode planningMode
    ) {
        return createCoding(client, environment, eventListener, planningMode,
                VerificationRepairPolicy.GUIDED_REPAIR);
    }

    /** Creates the CODE profile with an explicit verification-repair policy for controlled ablations. */
    public static Agent createCoding(
            LLMClient client,
            AgentEnvironment environment,
            AgentEventListener eventListener,
            PlanningMode planningMode,
            VerificationRepairPolicy repairPolicy
    ) {
        return new Agent(
                Objects.requireNonNull(client, "client must not be null"),
                Objects.requireNonNull(environment, "environment must not be null"),
                V1_SYSTEM_PROMPT,
                Agent.MAX_ITERATIONS,
                TaskMode.CODE_MODIFICATION,
                true,
                Objects.requireNonNull(eventListener, "eventListener must not be null"),
                true,
                Objects.requireNonNull(planningMode, "planningMode must not be null"),
                Objects.requireNonNull(repairPolicy, "repairPolicy must not be null")
        );
    }

    private static Path normalize(Path workspace) {
        return Objects.requireNonNull(workspace, "workspace must not be null")
                .toAbsolutePath()
                .normalize();
    }
}
