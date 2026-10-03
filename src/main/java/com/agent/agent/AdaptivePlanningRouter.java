package com.agent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, provider-free complexity router for individual CODE turns. */
public final class AdaptivePlanningRouter {
    private static final Pattern FILE_TARGET = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:[A-Za-z0-9_.-]+[/\\\\])*[A-Za-z0-9_.-]+\\.(?:java|py|md|cpp|c|h|hpp|json|ya?ml|xml|properties|txt)"
    );
    private static final Pattern ORDERED_ACTIONS = Pattern.compile(
            "(?:先.{0,100}(?:然后|再|接着)|first\\b.{0,100}\\bthen\\b|\\bthen\\b|after that)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern VERIFICATION_OR_RECOVERY = Pattern.compile(
            "(?:运行|执行|通过|失败后|如果.{0,20}失败|验证).{0,30}(?:测试|test|compile|编译)|"
                    + "(?:测试|test).{0,20}(?:失败|fail|通过|pass)|"
                    + "(?:run|execute|verify|compile|test)[\\w ]{0,30}(?:tests?|failure|error)|"
                    + "(?:tests?\\s+and\\s+fix|if\\s+tests?\\s+fail|fix\\s+failures)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MUTATION = Pattern.compile(
            "(?:修改|修复|修一下|改一下|改成|替换|更新|实现|新增|增加|添加|创建|重构|"
                    + "\\b(?:edit|modify|change|replace|update|fix|implement|create|refactor)\\b)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DISCOVERY = Pattern.compile(
            "(?:找到|查找|搜索|定位|find|locate|search|identify)", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MULTIPLE_REQUIREMENTS = Pattern.compile(
            "(?:同时|另外|并且|以及|两个|多个|两处|各自|以下\\s*\\d|"
                    + "(?:^|\\n)\\s*\\d+[.)、]|\\bboth\\b|\\btwo\\b|\\balso\\b)",
            Pattern.CASE_INSENSITIVE
    );

    public AdaptivePlanningDecision route(String rawUserTask) {
        String task = Objects.requireNonNull(rawUserTask, "rawUserTask must not be null")
                .toLowerCase(Locale.ROOT).trim();
        List<String> reasons = new ArrayList<>();
        int complexitySignals = 0;

        if (ORDERED_ACTIONS.matcher(task).find()) {
            reasons.add("ORDERED_ACTIONS");
            complexitySignals += 2;
        }
        if (MULTIPLE_REQUIREMENTS.matcher(task).find() || repeatedMutationActions(task)) {
            reasons.add("MULTIPLE_REQUIREMENTS");
            complexitySignals += 2;
        }

        List<String> targets = fileTargets(task);
        boolean crossFileDependency = (targets.size() > 1 && MUTATION.matcher(task).find())
                || (targets.size() > 1 && containsReadVerb(task))
                || task.contains("multiple files") || task.contains("多个文件");
        if (crossFileDependency) {
            reasons.add("CROSS_FILE_DEPENDENCY");
            complexitySignals += 2;
        }

        boolean verification = VERIFICATION_OR_RECOVERY.matcher(task).find();
        if (verification && MUTATION.matcher(task).find()) {
            reasons.add("VERIFICATION_OR_RECOVERY");
            complexitySignals += 2;
        }

        if (MUTATION.matcher(task).find() && DISCOVERY.matcher(task).find() && targets.isEmpty()) {
            reasons.add("TARGET_DISCOVERY_REQUIRED");
            complexitySignals += 2;
        }

        if (complexitySignals >= 2) {
            return new AdaptivePlanningDecision(PlanningMode.PLAN_EXECUTE,
                    AdaptivePlanningDecision.Confidence.HIGH, reasons);
        }
        if (!targets.isEmpty() && MUTATION.matcher(task).find()) {
            return new AdaptivePlanningDecision(PlanningMode.REACTIVE,
                    AdaptivePlanningDecision.Confidence.HIGH, List.of("SINGLE_EXPLICIT_TARGET"));
        }
        return new AdaptivePlanningDecision(PlanningMode.REACTIVE,
                AdaptivePlanningDecision.Confidence.LOW, List.of("INSUFFICIENT_COMPLEXITY_EVIDENCE"));
    }

    private static List<String> fileTargets(String task) {
        Matcher matcher = FILE_TARGET.matcher(task);
        List<String> targets = new ArrayList<>();
        while (matcher.find()) {
            String target = matcher.group().toLowerCase(Locale.ROOT);
            if (!targets.contains(target)) {
                targets.add(target);
            }
        }
        return targets;
    }

    private static boolean containsReadVerb(String task) {
        return task.contains("读取") || task.contains("查看") || task.contains("读一下")
                || task.matches("(?s).*\\b(?:read|inspect|compare)\\b.*");
    }

    private static boolean repeatedMutationActions(String task) {
        Matcher matcher = MUTATION.matcher(task);
        return matcher.find() && matcher.find();
    }
}
