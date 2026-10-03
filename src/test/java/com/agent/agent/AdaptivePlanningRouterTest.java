package com.agent.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptivePlanningRouterTest {
    private final AdaptivePlanningRouter router = new AdaptivePlanningRouter();

    @Test
    void simpleExplicitSingleFileEditsStayReactiveInChineseAndEnglish() {
        assertMode("把 README.md 中的 foo 改成 bar", PlanningMode.REACTIVE);
        assertMode("Replace TODO with DONE in notes.txt", PlanningMode.REACTIVE);
    }

    @Test
    void orderedLocateEditAndVerificationUsesPlanExecute() {
        assertMode("先找到 Calculator.java 中的 add 方法，然后修复并运行测试", PlanningMode.PLAN_EXECUTE);
        assertMode("Find Parser.java, edit it, then run tests and fix any failures", PlanningMode.PLAN_EXECUTE);
        assertMode("Find where the parser is implemented and fix the implementation", PlanningMode.PLAN_EXECUTE);
    }

    @Test
    void twoIndependentRequirementsUsePlanExecute() {
        assertMode("完成两项修改：1. 更新 App.java；2. 修复 Parser.java", PlanningMode.PLAN_EXECUTE);
        assertMode("Update both App.java and Parser.java independently", PlanningMode.PLAN_EXECUTE);
    }

    @Test
    void crossFileReadAndEditUsesPlanExecute() {
        assertMode("Read config.yml and modify Settings.java to use the new option", PlanningMode.PLAN_EXECUTE);
        assertMode("Update both config.yml and Settings.java", PlanningMode.PLAN_EXECUTE);
    }

    @Test
    void explicitVerificationAndRecoveryUsesPlanExecute() {
        assertMode("Run tests and fix failures in notes.txt", PlanningMode.PLAN_EXECUTE);
        assertMode("修改 App.java，并在测试失败后继续修复", PlanningMode.PLAN_EXECUTE);
    }

    @Test
    void testInFilenameDoesNotTriggerPlanning() {
        assertMode("Change one line in Test.java", PlanningMode.REACTIVE);
    }

    @Test
    void ambiguousTaskDefaultsConservativelyToReactive() {
        AdaptivePlanningDecision decision = router.route("Can you improve this?");
        assertEquals(PlanningMode.REACTIVE, decision.selectedMode());
        assertEquals(AdaptivePlanningDecision.Confidence.LOW, decision.confidence());
    }

    @Test
    void decisionsExposeStableConfidenceAndReasonCodes() {
        AdaptivePlanningDecision simple = router.route("Replace TODO with DONE in notes.txt");
        assertEquals(AdaptivePlanningDecision.Confidence.HIGH, simple.confidence());
        assertEquals(List.of("SINGLE_EXPLICIT_TARGET"), simple.reasons());

        AdaptivePlanningDecision complex = router.route(
                "Read config.yml and modify Settings.java, then run tests");
        assertEquals(AdaptivePlanningDecision.Confidence.HIGH, complex.confidence());
        assertTrue(complex.reasons().contains("ORDERED_ACTIONS"));
        assertTrue(complex.reasons().contains("CROSS_FILE_DEPENDENCY"));
        assertTrue(complex.reasons().contains("VERIFICATION_OR_RECOVERY"));
    }

    @Test
    void planningBenchmarkNamesAreNotComplexityRules() {
        assertMode("Update the TODO in planning_dev_01_multistep_java.md", PlanningMode.REACTIVE);
    }

    private void assertMode(String task, PlanningMode expected) {
        assertEquals(expected, router.route(task).selectedMode(), task);
    }
}
