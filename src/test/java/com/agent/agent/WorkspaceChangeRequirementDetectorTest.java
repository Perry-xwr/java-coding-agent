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
}
