package com.agent;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deterministically chooses an existing CLI profile for one AUTO-mode message.
 * This class has no model, tool, session, or history ownership.
 */
public final class CliIntentRouter {
    private static final List<String> WORKSPACE_REFERENCES = List.of(
            "readme", "dockerfile", "makefile", "src/", "src\\",
            "这个文件", "该文件", "这个项目", "该项目", "这个函数", "该函数", "这个类", "该类",
            "仓库", "当前目录", "该目录", "该目录下", "这个目录", "此目录", "目录下",
            "当前文件夹", "该文件夹", "这个文件夹", "这里",
            "当前项目", "当前 maven", "项目里", "工作区",
            "repository", "workspace", "this file", "this project"
    );
    private static final List<String> MUTATION_INTENTS = List.of(
            "修改", "修复", "修一下", "修掉", "改一下", "改清楚", "改成", "增加", "新增", "加一个", "删除",
            "添加", "加入", "插入", "创建", "新建", "更新", "优化", "处理",
            "edit", "fix", "implement", "create", "update", "delete",
            "add method", "add function"
    );
    private static final List<String> DESCRIPTIVE_IMPLEMENTATION_PHRASES = List.of(
            "实现了", "已经实现", "目前实现", "实现情况", "项目实现情况"
    );
    private static final List<String> READ_INTENTS = List.of(
            "读取", "查看", "搜索", "项目里有没有", "readme",
            "看看", "分析", "解释", "检查", "为什么", "原因", "总结", "找到",
            "read", "inspect", "search", "find", "analyze", "explain", "check", "why", "summarize"
    );
    private static final List<String> FILE_DISCOVERY_ACTIONS = List.of(
            "找", "查找", "列出", "有哪些", "所有", "find", "list", "show"
    );
    private static final List<String> FILE_NOUNS = List.of("文件", "file", "files");
    private static final List<String> CONTENT_SEARCH_SIGNALS = List.of(
            "代码里", "字符串", "内容包含", "哪里出现", "哪里调用", "调用了",
            "search string", "search code", "content contains", "where is", "where does"
    );
    private static final List<String> CODE_TARGETS = List.of(
            "代码", "方法", "函数", "类", "parser", "method", "function", "class"
    );
    private static final List<String> WORKSPACE_EXECUTION_SIGNALS = List.of(
            "运行 maven", "执行 maven", "运行测试", "执行测试", "测试当前项目",
            "run maven", "run tests", "execute tests"
    );
    private static final List<String> NO_MODIFICATION_CONSTRAINTS = List.of(
            "不要修改", "不要改", "先不要修改", "先不要改", "不修改", "只读", "read-only"
    );
    private static final List<String> EXPLICIT_FILE_CREATION = List.of(
            "创建文件", "新建文件", "create file"
    );
    private static final List<String> FILE_CREATION_ACTIONS = List.of(
            "创建", "新建", "create"
    );
    private static final List<String> GENERIC_FILE_ADDITION_ACTIONS = List.of(
            "增加一个", "添加一个"
    );
    private static final List<String> GENERIC_FILE_CREATION_TARGETS = List.of(
            "java文件", "java 文件", "python文件", "python 文件", "配置文件", "文本文件",
            "源码文件", "readme", "java类", "java 类"
    );
    private static final Pattern EXPLICIT_RELATIVE_FILE_REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_.\\\\/-])(?:[A-Za-z0-9_.-]+[\\\\/])*[A-Za-z0-9_-]+\\.[A-Za-z][A-Za-z0-9_-]*(?![A-Za-z0-9_.-])"
    );
    private static final List<String> GENERAL_KNOWLEDGE_FILE_QUESTIONS = List.of(
            "是什么", "是干什么", "有什么区别", "一般怎么", "怎么写"
    );
    private static final List<String> GENERAL_TECHNICAL_QUESTION_SIGNALS = List.of(
            "怎么", "如何", "什么是", "是什么", "有什么区别", "需要注意什么"
    );
    private static final List<String> CONTEXT_DEPENDENT_ACTIONS = List.of(
            "读取", "读", "查看", "看", "打开", "分析", "检查", "修改", "改", "处理", "继续",
            "read", "open", "analyze", "inspect", "edit", "continue"
    );

    public RoutingDecision route(String userMessage) {
        String normalized = Objects.requireNonNull(userMessage, "userMessage must not be null")
                .toLowerCase(Locale.ROOT);
        boolean explicitFileReference = hasExplicitFileReference(normalized);
        boolean workspaceReference = hasWorkspaceReference(normalized) || explicitFileReference;
        boolean mutationIntent = hasMutationIntent(normalized) && !hasNoModificationConstraint(normalized);
        boolean workspaceExecution = hasWorkspaceExecutionSignal(normalized);
        if (hasExplicitFileCreation(normalized)) {
            return decision(CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_FILE_CREATION);
        }
        if (!workspaceReference && isGeneralTechnicalQuestion(normalized)) {
            return decision(CliMode.CHAT, RoutingConfidence.HIGH, RoutingReason.GENERAL_KNOWLEDGE);
        }
        if (mutationIntent && (workspaceReference || hasCodeTarget(normalized) || workspaceExecution)) {
            return decision(CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_MUTATION_TARGET);
        }
        if (explicitFileReference || hasNamedProjectFile(normalized)) {
            if (!mutationIntent && !hasReadIntent(normalized) && isGeneralKnowledgeFileQuestion(normalized)) {
                return decision(CliMode.CHAT, RoutingConfidence.HIGH, RoutingReason.GENERAL_KNOWLEDGE);
            }
        }
        if (workspaceReference && hasReadIntent(normalized)) {
            return decision(CliMode.READ, RoutingConfidence.HIGH, RoutingReason.WORKSPACE_READ_REQUEST);
        }
        if (workspaceReference) {
            return decision(CliMode.READ, RoutingConfidence.MEDIUM, RoutingReason.AMBIGUOUS_WORKSPACE_REQUEST);
        }
        if (isIncompleteContextDependentRequest(normalized)) {
            return decision(CliMode.CHAT, RoutingConfidence.LOW, RoutingReason.INCOMPLETE_CONTEXTUAL_REQUEST);
        }
        if (hasFileDiscoveryIntent(normalized)) {
            return decision(CliMode.READ, RoutingConfidence.HIGH, RoutingReason.FILE_DISCOVERY_REQUEST);
        }
        if (hasContentSearchIntent(normalized)) {
            return decision(CliMode.READ, RoutingConfidence.HIGH, RoutingReason.WORKSPACE_READ_REQUEST);
        }
        return decision(CliMode.CHAT, RoutingConfidence.HIGH, RoutingReason.GENERAL_KNOWLEDGE);
    }

    private static boolean hasWorkspaceReference(String message) {
        return containsAny(message, WORKSPACE_REFERENCES);
    }

    private static boolean hasMutationIntent(String message) {
        return containsAny(message, MUTATION_INTENTS)
                || hasEnglishMutationVerb(message)
                || (message.contains("实现") && !isDescriptiveImplementationQuery(message));
    }

    private static boolean hasEnglishMutationVerb(String message) {
        return Pattern.compile("\\b(?:add|insert|modify|change)\\s+(?:a|an|the|new|[a-z0-9_.-]+(?:\\s+(?:method|function|file))?)\\b")
                .matcher(message)
                .find();
    }

    public boolean hasClearMutationIntent(String userMessage) {
        return hasMutationIntent(Objects.requireNonNull(userMessage, "userMessage must not be null")
                .toLowerCase(Locale.ROOT));
    }

    public boolean hasReadRequest(String userMessage) {
        return hasReadIntent(Objects.requireNonNull(userMessage, "userMessage must not be null")
                .toLowerCase(Locale.ROOT));
    }

    public boolean hasExplicitWorkspaceTarget(String userMessage) {
        String message = Objects.requireNonNull(userMessage, "userMessage must not be null")
                .toLowerCase(Locale.ROOT);
        return hasExplicitFileReference(message) || hasNamedProjectFile(message);
    }

    private static boolean isDescriptiveImplementationQuery(String message) {
        return containsAny(message, DESCRIPTIVE_IMPLEMENTATION_PHRASES)
                || (message.contains("哪些") && message.contains("实现"));
    }

    private static boolean hasReadIntent(String message) {
        return containsAny(message, READ_INTENTS);
    }

    private static boolean hasFileDiscoveryIntent(String message) {
        return containsAny(message, FILE_DISCOVERY_ACTIONS)
                && containsAny(message, FILE_NOUNS);
    }

    private static boolean hasContentSearchIntent(String message) {
        return containsAny(message, CONTENT_SEARCH_SIGNALS)
                && (containsAny(message, List.of("找", "搜索", "search", "find"))
                || message.contains("包含") || message.contains("调用"));
    }

    private static boolean hasCodeTarget(String message) {
        return containsAny(message, CODE_TARGETS);
    }

    private static boolean hasWorkspaceExecutionSignal(String message) {
        return containsAny(message, WORKSPACE_EXECUTION_SIGNALS);
    }

    private static boolean hasNoModificationConstraint(String message) {
        return containsAny(message, NO_MODIFICATION_CONSTRAINTS);
    }

    private static boolean hasExplicitFileCreation(String message) {
        return containsAny(message, EXPLICIT_FILE_CREATION)
                || (containsAny(message, FILE_CREATION_ACTIONS)
                && (hasExplicitFileReference(message) || hasGenericFileCreationTarget(message))
                && !isGeneralTechnicalQuestion(message))
                || (containsAny(message, GENERIC_FILE_ADDITION_ACTIONS)
                && hasGenericFileCreationTarget(message)
                && !isGeneralTechnicalQuestion(message));
    }

    private static boolean hasGenericFileCreationTarget(String message) {
        return containsAny(message, GENERIC_FILE_CREATION_TARGETS);
    }

    private static boolean hasExplicitFileReference(String message) {
        return EXPLICIT_RELATIVE_FILE_REFERENCE.matcher(message).find();
    }

    private static boolean hasNamedProjectFile(String message) {
        return message.contains("dockerfile") || message.contains("makefile");
    }

    private static boolean isGeneralKnowledgeFileQuestion(String message) {
        return containsAny(message, GENERAL_KNOWLEDGE_FILE_QUESTIONS);
    }

    private static boolean isGeneralTechnicalQuestion(String message) {
        return containsAny(message, GENERAL_TECHNICAL_QUESTION_SIGNALS);
    }

    private static boolean isIncompleteContextDependentRequest(String message) {
        return message.length() <= 32
                && !hasCodeTarget(message)
                && containsAny(message, CONTEXT_DEPENDENT_ACTIONS);
    }

    private static RoutingDecision decision(
            CliMode mode,
            RoutingConfidence confidence,
            RoutingReason reason
    ) {
        return new RoutingDecision(mode, confidence, reason);
    }

    private static boolean containsAny(String message, List<String> signals) {
        return signals.stream().anyMatch(message::contains);
    }
}
