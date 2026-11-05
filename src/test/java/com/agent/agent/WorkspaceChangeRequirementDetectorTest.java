package com.agent.agent;

import com.agent.CliIntentRouter;
import com.agent.CliMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceChangeRequirementDetectorTest {
    private final WorkspaceChangeRequirementDetector detector = new WorkspaceChangeRequirementDetector();
    private final CliIntentRouter router = new CliIntentRouter();

    @Test
    void clearWorkspaceChangesAreRequired() {
        assertEquals(WorkspaceChangeRequirementDetector.Requirement.REQUIRED,
                detector.detect("请在该目录下实现一个函数，用于解决汉诺塔问题"));
        assertEquals(WorkspaceChangeRequirementDetector.Requirement.REQUIRED,
                detector.detect("请在刚刚的代码里加一个函数，用于计算斐波那契数列"));
        assertEquals(WorkspaceChangeRequirementDetector.Requirement.REQUIRED,
                detector.detect("新建 hello.py"));
        assertEquals(WorkspaceChangeRequirementDetector.Requirement.REQUIRED,
                detector.detect("Fix Parser.java"));
    }

    @Test
    void commonExplicitMutationWordingIsRequired() {
        for (String wording : List.of(
                "修改 example.py",
                "给这个文件添加 foo",
                "新建 hello.py",
                "修复这个函数",
                "在刚刚的文件里加一个函数",
                "请在该目录下实现一个函数，用于解决汉诺塔问题",
                "请在刚刚的代码里加一个函数，用于计算斐波那契数列")) {
            assertEquals(WorkspaceChangeRequirementDetector.Requirement.REQUIRED,
                    detector.detect(wording), wording);
        }
    }

    @Test
    void readOnlyAndAdviceRequestsDoNotRequireMutation() {
        assertEquals(WorkspaceChangeRequirementDetector.Requirement.NOT_REQUIRED,
                detector.detect("解释一下 example.py 做了什么"));
        assertEquals(WorkspaceChangeRequirementDetector.Requirement.NOT_REQUIRED,
                detector.detect("检查这个函数可能有什么问题，但不要修改文件"));
        assertEquals(WorkspaceChangeRequirementDetector.Requirement.NOT_REQUIRED,
                detector.detect("如何创建一个 Java 文件？"));
        assertEquals(WorkspaceChangeRequirementDetector.Requirement.NOT_REQUIRED,
                detector.detect("Java 和 C++ 有什么区别？"));
        assertEquals(WorkspaceChangeRequirementDetector.Requirement.NOT_REQUIRED,
                detector.detect("什么是递归？"));
        assertEquals(CliMode.CHAT, router.route("Java 文件怎么创建？").mode());
        for (String wording : List.of(
                "解释 example.py",
                "分析这个函数",
                "告诉我应该怎么修改",
                "检查问题但不要修改",
                "不要修改文件，只告诉我原因")) {
            assertEquals(WorkspaceChangeRequirementDetector.Requirement.NOT_REQUIRED,
                    detector.detect(wording), wording);
        }
    }

    @Test
    void alreadySatisfiedAndVagueRequestsRemainUnknown() {
        assertEquals(WorkspaceChangeRequirementDetector.Requirement.UNKNOWN,
                detector.detect("确保文件里有 foo 函数"));
        assertEquals(WorkspaceChangeRequirementDetector.Requirement.UNKNOWN,
                detector.detect("帮我处理一下"));
        for (String wording : List.of("确保这个文件没问题", "看看这个文件", "处理一下这个代码")) {
            assertEquals(WorkspaceChangeRequirementDetector.Requirement.UNKNOWN,
                    detector.detect(wording), wording);
        }
    }

    @Test
    void unifiedCompletionRequirementDoesNotTreatAnalysisAsAnEditRequest() {
        for (String wording : List.of("解释 Calculator.add", "检查代码并告诉我问题")) {
            assertEquals(WorkspaceChangeRequirementDetector.Requirement.NOT_REQUIRED,
                    detector.detect(wording), wording);
        }
        assertEquals(WorkspaceChangeRequirementDetector.Requirement.UNKNOWN,
                detector.detect("比较两种算法"),
                "An ambiguous comparison must not activate the mutation completion guard");
        for (String wording : List.of(
                "修复 Calculator.add",
                "先检查，再修复，最后运行测试",
                "创建一个 Java 文件")) {
            assertEquals(WorkspaceChangeRequirementDetector.Requirement.REQUIRED,
                    detector.detect(wording), wording);
        }
    }
    @Test void conventionsAnalysisAndMixedMutationRequestsStayDistinct(){
        for(String input:List.of("以后这个项目统一使用 Java 17。","记住我们后续优先用 Maven。","解释一下这段代码。","帮我分析这个项目的架构。","刚才讨论的方案有哪些优缺点？","我们约定以后采用 Maven"))assertEquals(WorkspaceChangeRequirementDetector.Requirement.NOT_REQUIRED,detector.detect(input),input);
        for(String input:List.of("把 pom.xml 的 Java 版本改成 17。","帮我修复这个编译错误。","实现一个新的 Memory 提取器。","修改代码并运行测试。","以后使用 Java 17，并把 pom.xml 改成 17。"))assertEquals(WorkspaceChangeRequirementDetector.Requirement.REQUIRED,detector.detect(input),input);
        assertEquals(WorkspaceChangeRequirementDetector.Requirement.UNKNOWN,detector.detect("帮我处理一下"));
    }

    @Test void explicitConversationAndNoChangeLanguageDoesNotRequireMutationButMixedPositiveEditStillDoes(){
        for(String input:List.of(
                "这次只是交流，不需要修改任何文件。",
                "这次只是交流，分享几项长期技术偏好，不需要改动项目文件。",
                "讨论新增功能前应该做什么？这次只是交流，不需要修改任何文件。",
                "这轮只讨论，不修改文件。",
                "仅讨论开发习惯，无需编辑文档。",
                "只是问答，不需要修改文件。"))
            assertEquals(WorkspaceChangeRequirementDetector.Requirement.NOT_REQUIRED,detector.detect(input),input);
        assertEquals(WorkspaceChangeRequirementDetector.Requirement.REQUIRED,
                detector.detect("请修改 pom.xml 的 Java 版本，但不需要修改其他文件。"));
        assertEquals(WorkspaceChangeRequirementDetector.Requirement.REQUIRED,
                detector.detect("把 pom.xml 的 Java 版本改为 17，但不需要修改其他文件。"));
    }

    @Test void historicalRecallIsReadOnlyButAnExplicitEditBasedOnHistoryStillRequiresMutation() {
        for (String wording : List.of(
                "按照我之前的计划，我周日晚上要做什么",
                "根据上次讨论，我们决定了什么？",
                "搜索之前关于 Memory 的讨论并总结待办")) {
            assertEquals(WorkspaceChangeRequirementDetector.Requirement.NOT_REQUIRED,
                    detector.detect(wording), wording);
        }
        assertEquals(WorkspaceChangeRequirementDetector.Requirement.REQUIRED,
                detector.detect("根据之前的计划，修改 pom.xml 的版本"));
    }

    @Test void futureFacingCodingPreferencesAreNotCurrentWorkspaceChangesButExplicitMixedEditsRemainRequired() {
        for (String wording : List.of(
                "我希望以后在这个项目里编写 Python 代码时，函数尽量添加类型注解。这是我长期坚持的开发习惯。",
                "以后写 Python 函数时尽量添加类型注解。",
                "以后修改 Java 代码时先运行测试。",
                "我希望今后生成的代码更简洁。")) {
            assertEquals(WorkspaceChangeRequirementDetector.Requirement.NOT_REQUIRED,
                    detector.detect(wording), wording);
        }
        for (String wording : List.of(
                "现在创建一个 Python 文件。",
                "帮我修改 main.py，给函数添加类型注解。",
                "按照刚才的约定，修改现有 Java 文件。",
                "以后写 Python 时加类型注解，现在帮我创建一个 hello.py。")) {
            assertEquals(WorkspaceChangeRequirementDetector.Requirement.REQUIRED,
                    detector.detect(wording), wording);
        }
    }
}
