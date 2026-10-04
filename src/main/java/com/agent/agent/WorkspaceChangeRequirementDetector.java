package com.agent.agent;

import com.agent.CliIntentRouter;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Classifies whether a user turn clearly requires a workspace mutation. */
public final class WorkspaceChangeRequirementDetector {
    public enum Requirement {
        REQUIRED,
        NOT_REQUIRED,
        UNKNOWN
    }

    private static final List<String> NO_CHANGE_REQUESTS = List.of(
            "不要修改", "不要改", "先不要修改", "先不要改", "不修改", "只读", "read-only",
            "without changing", "do not modify", "don't modify", "do not edit", "don't edit"
    );
    private static final List<String> INSTRUCTIONAL_QUESTIONS = List.of(
            "怎么", "如何", "需要注意什么", "告诉我怎么", "告诉我如何", "what is", "how to", "how do i"
    );
    private static final List<String> MUTATION_ACTIONS = List.of(
            "创建", "新建", "添加", "加一个", "修改", "改成", "改为", "更新", "修复", "修一下",
            "插入", "替换", "重写", "实现", "写入", "保存到",
            "create", "add", "modify", "change", "update", "fix", "insert", "replace", "rewrite",
            "implement", "write"
    );
    private static final List<String> WORKSPACE_TARGETS = List.of(
            "目录", "文件夹", "工作区", "仓库", "项目", "文件", "代码", "源码", "函数", "方法", "类",
            "刚刚", "刚才", "这个", "该文件", "this file", "the file", "workspace", "repository", "repo",
            "folder", "directory", "file", "code", "function", "method", "class"
    );
    private static final List<String> ANALYSIS_ONLY_INTENTS = List.of(
            "解释", "分析", "检查", "查看", "读取", "阅读", "搜索", "总结", "说明",
            "explain", "analyze", "inspect", "check", "read", "search", "summarize"
    );
    private static final Pattern EXPLICIT_FILE = Pattern.compile(
            "(?i)(?:^|[^a-z0-9_])[^\\s\\\\/:*?\"<>|]+\\.(?:java|py|js|ts|cpp|c|h|hpp|md|txt|ya?ml|json|xml)(?:$|[^a-z0-9_])"
    );

    private final CliIntentRouter intentRouter;

    public WorkspaceChangeRequirementDetector() {
        this(new CliIntentRouter());
    }

    public WorkspaceChangeRequirementDetector(CliIntentRouter intentRouter) {
        this.intentRouter = Objects.requireNonNull(intentRouter, "intentRouter must not be null");
    }

    public Requirement detect(String userTurn) {
        String input = Objects.requireNonNull(userTurn, "userTurn must not be null")
                .toLowerCase(Locale.ROOT).trim();
        if (input.isEmpty()) {
            return Requirement.UNKNOWN;
        }
        if (containsAny(input, NO_CHANGE_REQUESTS) || isInstructionalQuestion(input)) {
            return Requirement.NOT_REQUIRED;
        }

        boolean routerRecognizesMutation = intentRouter.hasClearMutationIntent(input);
        boolean explicitMutationAction = containsAny(input, MUTATION_ACTIONS);
        if (!routerRecognizesMutation || !explicitMutationAction) {
            return containsAny(input, ANALYSIS_ONLY_INTENTS)
                    ? Requirement.NOT_REQUIRED : Requirement.UNKNOWN;
        }

        if (EXPLICIT_FILE.matcher(input).find() || containsAny(input, WORKSPACE_TARGETS)) {
            return Requirement.REQUIRED;
        }
        return Requirement.UNKNOWN;
    }

    private static boolean isInstructionalQuestion(String input) {
        return containsAny(input, INSTRUCTIONAL_QUESTIONS);
    }

    private static boolean containsAny(String input, List<String> candidates) {
        return candidates.stream().anyMatch(input::contains);
    }
}
