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

    /** One current-turn decision shared by Daily tool permissions and completion checks. */
    public record Decision(Requirement requirement, boolean workspaceReadsAllowed,
                           boolean workspaceWritesAllowed) {
        public Decision {
            Objects.requireNonNull(requirement, "requirement must not be null");
            if (workspaceWritesAllowed && requirement != Requirement.REQUIRED)
                throw new IllegalArgumentException("Writes require an explicit workspace-change request");
        }
    }

    private static final Pattern CHAT_ONLY_OUTPUT = Pattern.compile(
            "(?ius).*(?:只|仅)(?:在|用)?(?:聊天|对话)(?:中|里)?(?:给出|提供|回答|展示|写出|输出|回复|讨论).*" );
    private static final String FILE_OPERATION = "(?:读取|阅读|读|查看|打开|创建|新建|修改|改动|编辑|写入|删除|实现|修复|添加|read|open|view|create|modify|edit|write|delete|implement|fix|add)";
    private static final Pattern NEGATED_FILE_OPERATIONS = Pattern.compile(
            "(?iu)(?:不要|不允许|不能|不得|禁止|别|不|do not|don't|without)\\s*"
                    + "(?<operations>" + FILE_OPERATION + "(?:\\s*(?:、|或|和|及|并|,|or|and)\\s*" + FILE_OPERATION + ")*)"
                    + "[^。.!！？?]{0,24}(?:文件|代码|源码|文件系统|files?|code|workspace)" );
    private static final List<String> FILE_READ_OPERATIONS = List.of("读取", "阅读", "读", "查看", "打开", "read", "open", "view");
    private static final List<String> FILE_WRITE_OPERATIONS = List.of("创建", "新建", "修改", "改动", "编辑", "写入", "删除", "实现", "修复", "添加",
            "create", "modify", "edit", "write", "delete", "implement", "fix", "add");

    private static final List<String> NO_CHANGE_REQUESTS = List.of(
            "不要修改", "不要改", "先不要修改", "先不要改", "不修改", "只读", "read-only",
            "不需要修改", "无需修改", "不用修改", "不必修改", "只是交流", "仅是交流", "只交流",
            "只是讨论", "仅讨论", "只讨论", "just discussing", "just a discussion",
            "no need to modify", "no need to change", "no need to edit",
            "不要运行工具", "不运行工具", "不要使用工具", "不调用工具",
            "without changing", "do not modify", "don't modify", "do not edit", "don't edit"
    );
    private static final Pattern NEGATED_MUTATION = Pattern.compile(
            "(?iu)(?:不需要|无需|不用|不必|不要|别|不|no need to|do not|don't)\\s*(?:对|去)?\\s*"
                    + "(?:随便|擅自|自动|再|继续|随意)?"
                    + "(?:修改|改动|更改|编辑|写入|编写|创建|新增|删除|实现|修复|添加|modify|change|edit|write|create|add|delete|implement|fix)"
                    + "(?:任何|任意|任何一个|项目中的|工作区中的|any|anything|all)?(?:项目文件|文件|代码|文档|目录|内容|files?|code|documents?|directories|anything)?"
    );
    private static final Pattern AFFIRMATIVE_MUTATION = Pattern.compile(
            "(?iu)(?:修改|改动|更改|编辑|写入|编写|创建|新增|删除|实现|修复|添加|插入|替换|重写|改成|改为|保存到|"
                    + "\\b(?:modify|change|edit|write|create|add|delete|implement|fix|insert|replace|rewrite|update)\\b)"
    );
    private static final List<String> INSTRUCTIONAL_QUESTIONS = List.of(
            "怎么", "如何", "需要注意什么", "告诉我怎么", "告诉我如何",
            "是什么", "什么是", "有什么区别", "有什么不同", "区别是什么", "比较一下",
            "what is", "what's the difference", "what is the difference", "compare", "how to", "how do i"
    );
    private static final List<String> MUTATION_ACTIONS = List.of(
            "创建", "新建", "编写", "添加", "加一个", "修改", "改成", "改为", "更新", "修复", "修一下",
            "插入", "替换", "重写", "实现", "写入", "保存到",
            "create", "add", "modify", "change", "update", "fix", "insert", "replace", "rewrite",
            "implement", "write"
    );
    private static final List<String> WORKSPACE_TARGETS = List.of(
            "目录", "文件夹", "工作区", "仓库", "项目", "文件", "代码", "源码", "函数", "方法", "类",
            "刚刚", "刚才", "这个", "该文件", "this file", "the file", "workspace", "repository", "repo",
            "folder", "directory", "file", "code", "function", "method", "class", "测试", "test", "提取器", "组件", "模块", "功能"
    );
    private static final List<String> ANALYSIS_ONLY_INTENTS = List.of(
            "解释", "分析", "检查", "查看", "读取", "阅读", "搜索", "总结", "说明",
            "explain", "analyze", "inspect", "check", "read", "search", "summarize"
    );
    private static final List<String> MEMORY_STATEMENT_TERMS = List.of(
            "记住", "记忆", "偏好", "习惯", "memory", "remember"
    );
    private static final List<String> HISTORY_REFERENCES = List.of(
            "之前", "此前", "上次", "以前", "刚才", "刚刚", "前面", "历史会话", "历史记录",
            "我们约定", "我们讨论", "之前的计划", "上次的计划", "previous", "earlier", "last time", "history"
    );
    private static final List<String> HISTORY_RECALL_CUES = List.of(
            "什么", "哪", "哪里", "怎么", "计划", "安排", "约定", "决定", "讨论", "进度", "回忆", "记得",
            "查找", "搜索", "查询", "读取", "总结", "继续", "what", "which", "where", "recall", "retrieve", "summarize"
    );
    private static final Pattern EXPLICIT_FILE = Pattern.compile(
            "(?i)(?:^|[^a-z0-9_])[^\\s\\\\/:*?\"<>|]+\\.(?:java|py|js|ts|cpp|c|h|hpp|md|txt|ya?ml|json|xml)(?:$|[^a-z0-9_])"
    );
    private static final Pattern QUALIFIED_CODE_SYMBOL = Pattern.compile(
            "(?<![A-Za-z0-9_$])(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+[A-Za-z_$][A-Za-z0-9_$]*(?![A-Za-z0-9_$])"
    );
    private static final Pattern GENERAL_QUESTION = Pattern.compile(
            "(?iu).*(?:[?？]|应该做什么|有哪些|有什么|是否|如何|怎么|什么|为什么|\\bhow\\b|\\bwhy\\b|\\bwhat\\b).*" );
    private static final Pattern INFORMATIONAL_LEAD = Pattern.compile(
            "(?iu)^(?:(?:请你?|帮我|麻烦你?|能否|可以|please|can you)\\s*)*(?:解释|分析|总结|查询|查找|搜索|说明|介绍|告诉我|列出|读取|查看|阅读|检查|讨论|评估|我想知道|explain\\b|analy[sz]e\\b|summari[sz]e\\b|search\\b|read\\b|describe\\b|list\\b|inspect\\b).*" );
    private static final Pattern CODING_GUIDANCE = Pattern.compile(
            "(?iu).*(?:修改代码|修改文件|写代码|创建文件|编写代码).*(?:前|之前).*(?:应该|应当|必须|需要|先).*" );

    private final CliIntentRouter intentRouter;

    public WorkspaceChangeRequirementDetector() {
        this(new CliIntentRouter());
    }

    public WorkspaceChangeRequirementDetector(CliIntentRouter intentRouter) {
        this.intentRouter = Objects.requireNonNull(intentRouter, "intentRouter must not be null");
    }

    public Requirement detect(String userTurn) {
        return decide(userTurn).requirement();
    }

    public Decision decide(String userTurn) {
        String text = Objects.requireNonNull(userTurn, "userTurn must not be null");
        boolean chatOnly = CHAT_ONLY_OUTPUT.matcher(text).matches();
        boolean noFileReads = chatOnly;
        boolean noFileWrites = chatOnly;
        var restrictions = NEGATED_FILE_OPERATIONS.matcher(text);
        while (restrictions.find()) {
            String operations = restrictions.group("operations").toLowerCase(Locale.ROOT);
            noFileReads |= FILE_READ_OPERATIONS.stream().anyMatch(operations::contains);
            noFileWrites |= FILE_WRITE_OPERATIONS.stream().anyMatch(operations::contains);
        }

        Requirement requirement = classifyRequirement(text);
        // A current-turn prohibition is authoritative when it conflicts with a
        // mutation cue elsewhere in the same request. The Agent must not demand a
        // mutation that the Daily tool gate is explicitly forbidden to perform.
        if (noFileWrites) requirement = Requirement.NOT_REQUIRED;
        return new Decision(requirement, !noFileReads,
                requirement == Requirement.REQUIRED && !noFileWrites);
    }

    private Requirement classifyRequirement(String userTurn) {
        String input = Objects.requireNonNull(userTurn, "userTurn must not be null")
                .toLowerCase(Locale.ROOT).trim();
        if (input.isEmpty()) {
            return Requirement.UNKNOWN;
        }
        if (isDescriptiveMutationTopic(input)) {
            return Requirement.NOT_REQUIRED;
        }
        // A named-file edit command is decisive even when the same sentence also
        // contains a general no-change disclaimer (for example, “edit pom.xml,
        // but do not change other files”).
        if (hasTargetedFileMutationCommand(input)) {
            return Requirement.REQUIRED;
        }
        // A fully recognized durable policy is user data, even when it forbids creating files.
        // Do not let that shortcut hide a concrete edit command included in the same turn.
        if (com.agent.MemoryQualityPolicy.declaredPolicyOnly(input)) {
            return Requirement.NOT_REQUIRED;
        }
        // An exact greeting is not an ambiguous request to modify the workspace.
        if (input.matches("(?iu)(你好|您好|hello|hi|hey|谢谢|thanks|thank you|早上好|晚上好|你是谁|who are you)[!！?？。,.，\\s]*")) {
            return Requirement.NOT_REQUIRED;
        }
        boolean explicitNoChange = containsAny(input, NO_CHANGE_REQUESTS)
                || input.matches("(?iu).*(?:这次|本次|这轮|当前)?\\s*(?:只是|仅是|只是在|仅仅是|只)?\\s*(?:交流|讨论|问答|咨询)(?:一下|而已)?(?:[，,。.!！?？\\s].*)?$");
        if (explicitNoChange) {
            if (!hasAffirmativeMutationIntent(input)) {
                return Requirement.NOT_REQUIRED;
            }
            if (EXPLICIT_FILE.matcher(input).find() || QUALIFIED_CODE_SYMBOL.matcher(input).find()
                    || containsAny(input, WORKSPACE_TARGETS)) {
                return Requirement.REQUIRED;
            }
        }
        // A future-facing preference can mention actions such as “write code” or
        // “add annotations” without asking us to perform them in this turn. Keep
        // concrete current edits (including mixed preference + edit requests) on
        // the normal mutation path.
        if (isFuturePreferenceDeclaration(input) && !hasCurrentMutationRequest(input)) {
            return Requirement.NOT_REQUIRED;
        }
        if (CODING_GUIDANCE.matcher(input).matches() && !hasCurrentMutationRequest(input)) {
            return Requirement.NOT_REQUIRED;
        }
        if ((GENERAL_QUESTION.matcher(input).matches() || INFORMATIONAL_LEAD.matcher(input).matches())
                && !hasCurrentMutationRequest(input)) {
            return Requirement.NOT_REQUIRED;
        }
        // Memory statements describe whether information should be retained by the
        // Memory subsystem; they are not requests to mutate workspace files. Keep
        // explicit workspace edits on the normal mutation path.
        if (containsAny(input, MEMORY_STATEMENT_TERMS)
                && !hasAffirmativeMutationIntent(input)
                && (!intentRouter.hasClearMutationIntent(input)
                    || com.agent.MemoryQualityPolicy.lowRisk(input))
                && !(intentRouter.hasExplicitWorkspaceTarget(input)
                    && AFFIRMATIVE_MUTATION.matcher(input).find())) {
            return Requirement.NOT_REQUIRED;
        }
        // A recognized durable preference is user-provided information, even if
        // its subject contains words such as “performance optimization”. The
        // preference taxonomy is deliberately narrow; imperative edit requests
        // are still caught by the explicit-command checks above/below.
        if (com.agent.MemoryQualityPolicy.lowRisk(input)
                && !hasAffirmativeMutationIntent(input)) {
            return Requirement.NOT_REQUIRED;
        }
        if (isInstructionalQuestion(input)) {
            return Requirement.NOT_REQUIRED;
        }
        // Recalling prior conversation is a read-only task even when phrased as
        // an unpunctuated natural-language question (for example, “按之前计划，
        // 我周日晚上要做什么”). Keep explicit edit requests on the mutation path.
        if (!hasAffirmativeMutationIntent(input) && isHistoricalRecallRequest(input)) {
            return Requirement.NOT_REQUIRED;
        }

        boolean routerRecognizesMutation = intentRouter.hasClearMutationIntent(input);
        boolean explicitMutationAction = containsAny(input, MUTATION_ACTIONS);
        // A durable convention is data supplied by the user, not an instruction to edit files.
        // Mixed requests with edit verbs retain the mutation/unknown checks below.
        if(!explicitMutationAction&&!input.matches("(?s).*(删除|清理|执行|运行|shell|sudo|powershell|cmd\\.exe).*")){
            boolean convention=input.matches("(?s)^(?:请?记住[，,\\s]*)?(?:(?:以后|今后|后续|从现在起)[，,\\s]*)?(?:这个项目|本项目|该项目|项目|我们|我).*(?:使用|采用|优先用|遵循|偏好|约定|决定).+");
            boolean comparison=input.matches("(?s).*(优缺点|利弊|pros and cons|trade.?offs).*" );
            // Teaching/discussion is not an ambiguous request to mutate workspace files.
            // Explicit mutation or execution wording still follows the existing guard path.
            boolean learning=input.matches("(?s).*(讲解|讲讲|讲一下|开始讲|继续讲|接着讲|学习|学到哪里|学到哪|讨论|请教|\\bteach\\b|\\blearn\\b|\\bdiscuss\\b).*" );
            boolean question=input.endsWith("？")||input.endsWith("?");
            if(convention||comparison||learning||question)return Requirement.NOT_REQUIRED;
        }
        if (!routerRecognizesMutation || !explicitMutationAction) {
            return containsAny(input, ANALYSIS_ONLY_INTENTS)
                    ? Requirement.NOT_REQUIRED : Requirement.UNKNOWN;
        }

        if (EXPLICIT_FILE.matcher(input).find() || QUALIFIED_CODE_SYMBOL.matcher(input).find()
                || containsAny(input, WORKSPACE_TARGETS)) {
            return Requirement.REQUIRED;
        }
        return Requirement.UNKNOWN;
    }

    private static boolean isInstructionalQuestion(String input) {
        return containsAny(input, INSTRUCTIONAL_QUESTIONS);
    }

    private static boolean isHistoricalRecallRequest(String input) {
        return containsAny(input, HISTORY_REFERENCES) && containsAny(input, HISTORY_RECALL_CUES);
    }

    private static boolean isFuturePreferenceDeclaration(String input) {
        boolean futureFacing = input.matches("(?iu).*(?:以后|今后|后续|从现在起|长期|in the future|from now on|going forward).*");
        boolean preferenceLanguage = input.matches("(?iu).*(?:偏好|习惯|希望|倾向|尽量|每次|始终|总是|先|应该|应当|必须|prefer|preference|habit|would like).*");
        return futureFacing && preferenceLanguage;
    }

    private static boolean isDescriptiveMutationTopic(String input) {
        return input.matches("(?iu).*(?:修改|创建|编写|实现|删除|修复|更新)(?:代码|文件|文档)?(?:的原则|的约定|的注意事项)[。.!！?？\\s]*");
    }

    private static boolean hasCurrentMutationRequest(String input) {
        for (String clause : input.split("[，,。.!！?？;；\\r\\n]|但是|但|不过|同时|另外|并且|然后|并")) {
            String current = clause.strip();
            if (current.isEmpty() || current.matches("(?iu)^(?:以后|今后|后续|从现在起|长期).*")) {
                continue;
            }
            if (current.matches("(?iu).*(?:代码|文件|文档|功能)(?:前|之前)(?:应该|应当|要|需要|必须|有什么|有哪些|做什么).*")) {
                continue;
            }
            boolean currentAction = current.matches("(?iu)^(?:现在|立刻|马上|请|帮我|为我|把|将|直接|请你|please|now|create|modify|edit|write|add|fix).*" )
                    || current.matches("(?iu)^(?:创建|新建|编写|修改|编辑|添加|实现|修复|更新|写入|替换|重写).*" );
            if (currentAction && (AFFIRMATIVE_MUTATION.matcher(current).find()
                    || MUTATION_ACTIONS.stream().anyMatch(current::contains))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String input, List<String> candidates) {
        return candidates.stream().anyMatch(input::contains);
    }

    private boolean hasAffirmativeMutationIntent(String input) {
        String commandText=input.replaceFirst("^(?:请)?(?:记住|记录(?:一下)?|保存(?:这个|该)?(?:偏好|习惯)?)[，,：:\\s]*", "");
        return java.util.Arrays.stream(commandText.split("[，,。.!！?？;；\\r\\n]|但是|但|不过|同时|另外|并且|然后|并"))
                .map(String::strip)
                .anyMatch(WorkspaceChangeRequirementDetector::isExplicitMutationCommandClause);
    }

    private static boolean hasTargetedFileMutationCommand(String input) {
        // Keep the dot in filenames such as pom.xml while locating clause boundaries.
        String firstClause = input.split("[，,。!！?？;；\\r\\n]|但是|但|不过|同时|另外|并且|然后|并", 2)[0].strip();
        boolean imperative = firstClause.startsWith("把") || firstClause.startsWith("将")
                || firstClause.startsWith("请修改") || firstClause.startsWith("请更新")
                || firstClause.startsWith("请编辑") || firstClause.startsWith("帮我修改")
                || firstClause.startsWith("帮我更新") || firstClause.startsWith("帮我编辑");
        return imperative && EXPLICIT_FILE.matcher(firstClause).find()
                && AFFIRMATIVE_MUTATION.matcher(firstClause).find();
    }

    private static boolean isExplicitMutationCommandClause(String clause) {
        String command = clause;
        for (String prefix : List.of("但是", "不过", "同时", "另外", "然后", "并且", "但", "并",
                "请", "帮我", "为我", "现在", "直接", "马上", "please", "now")) {
            if (command.startsWith(prefix)) {
                command = command.substring(prefix.length()).stripLeading();
            }
        }
        if (command.startsWith("把") || command.startsWith("将")) {
            return AFFIRMATIVE_MUTATION.matcher(command.substring(1)).find();
        }
        return command.startsWith("优化") || command.startsWith("调优")
                || MUTATION_ACTIONS.stream().anyMatch(command::startsWith)
                || command.matches("(?iu)^(?:modify|change|edit|write|create|add|delete|implement|fix|insert|replace|rewrite|update)\\b.*");
    }
}
