package com.agent;

import com.agent.agent.WorkspaceChangeRequirementDetector;
import java.util.regex.Pattern;

/** Per-turn capability ceiling from the current user request, never from model plans/history. */
final class DailyToolIntentPolicy {
    private static final Pattern VERIFY_COMMAND = Pattern.compile(
            "(?is)^(?:(?:请你?|帮我|麻烦你?|please)\\s*)*(?:运行|执行|检查|验证|run\\b|execute\\b|check\\b|verify\\b).*(?:maven|测试|编译|tests?\\b|compil).*");
    private final com.agent.agent.WorkspaceChangeRequirementDetector.Decision decision;
    private final boolean verification;

    DailyToolIntentPolicy(String userRequest) {
        decision = new WorkspaceChangeRequirementDetector().decide(userRequest);
        verification = decision.workspaceWritesAllowed() || VERIFY_COMMAND.matcher(userRequest).matches();
    }

    boolean editsAllowed() { return decision.workspaceWritesAllowed(); }
    boolean fileReadsAllowed() { return decision.workspaceReadsAllowed(); }
    com.agent.agent.WorkspaceChangeRequirementDetector.Decision decision() { return decision; }
    boolean verificationAllowed() { return verification; }
}
