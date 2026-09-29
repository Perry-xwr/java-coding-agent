package com.agent.benchmark.v12;

import com.agent.CliMode;
import com.agent.agent.AgentActionType;
import com.agent.agent.AgentRunResult;
import com.agent.agent.AgentStep;
import com.agent.agent.AgentTrajectory;
import com.agent.agent.TerminationReason;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class V12ExecutionLayerTest {
    @TempDir Path temporary;

    @Test
    void scriptedAutoChatAndReadUseRealRouterProfilesAndTools() throws Exception {
        V12RuntimeHarness chat = new V12RuntimeHarness(scripted(finalAnswer("Java is a language.")), temporary);
        V12Task chatTask = task("chat", List.of("Java 是什么？"), Map.of("README.md","x"), V12Evaluator.ROUTE_AND_COMPLETION);
        List<V12TurnResult> chatTurns=chat.run(chatTask);
        assertEquals(CliMode.CHAT,chatTurns.get(0).selectedRoute());
        assertTrue(chatTurns.get(0).trajectory().completed());

        Files.writeString(temporary.resolve("README.md"),"# Demo");
        V12RuntimeHarness read = new V12RuntimeHarness(scripted(call("read_file",Map.of("path","README.md")),finalAnswer("Demo")),temporary);
        V12Task readTask=task("read",List.of("读取 README.md"),Map.of("README.md","# Demo"),V12Evaluator.TOOL_TRAJECTORY);
        List<V12TurnResult> turns=read.run(readTask);
        assertEquals(CliMode.READ,turns.get(0).selectedRoute());
        assertEquals("read_file",toolSteps(turns).get(0).toolName());
    }

    @Test
    void scriptedCodeReadPatchRereadCreateAndCompletionUseRealTools() throws Exception {
        Files.writeString(temporary.resolve("note.txt"),"old\n");
        V12RuntimeHarness code=new V12RuntimeHarness(scripted(
                call("read_file",Map.of("path","note.txt")),
                call("apply_patch",Map.of("path","note.txt","oldText","old","newText","new")),
                call("read_file",Map.of("path","note.txt")), finalAnswer("done")),temporary);
        List<V12TurnResult> turns=code.run(task("code",List.of("修改 note.txt，把 old 改成 new"),Map.of("note.txt","old\n"),V12Evaluator.HIDDEN_FILE_CONTENT));
        assertEquals(CliMode.CODE,turns.get(0).selectedRoute());
        assertEquals(List.of("read_file","apply_patch","read_file"),toolSteps(turns).stream().map(AgentStep::toolName).toList());
        assertEquals("new\n",Files.readString(temporary.resolve("note.txt")));

        V12RuntimeHarness create=new V12RuntimeHarness(scripted(
                call("create_file",Map.of("path","made.txt","content","created\n")),
                call("read_file",Map.of("path","made.txt")),finalAnswer("created")),temporary);
        create.run(task("create",List.of("创建文件 made.txt，内容为 created"),Map.of("seed.txt","x"),V12Evaluator.HIDDEN_FILE_CONTENT));
        assertEquals("created\n",Files.readString(temporary.resolve("made.txt")));
    }

    @Test
    void explicitReadThenCodeUsesTwoRealSessions() throws Exception {
        Path workspace=temporary.resolve("follow"); Files.createDirectories(workspace); Files.writeString(workspace.resolve("Config.txt"),"port=80\n");
        V12RuntimeHarness runtime=new V12RuntimeHarness(scripted(
                call("read_file",Map.of("path","Config.txt")),finalAnswer("80"),
                call("read_file",Map.of("path","Config.txt")),
                call("apply_patch",Map.of("path","Config.txt","oldText","80","newText","8080")),
                call("read_file",Map.of("path","Config.txt")),finalAnswer("done")),workspace);
        V12Task task=new V12Task("follow",V12Split.DEV,"profile_followup",Map.of("Config.txt","port=80\n"),
                List.of("读取 Config.txt","把端口改成 8080"),List.of(CliMode.READ,CliMode.CODE),List.of("read","edit"),
                V12Evaluator.HIDDEN_FILE_CONTENT,8,List.of("FOLLOW_UP"),List.of("8080"));
        List<V12TurnResult> turns=runtime.run(task);
        assertEquals(List.of(CliMode.READ,CliMode.CODE),turns.stream().map(V12TurnResult::selectedRoute).toList());
        assertTrue(Files.readString(workspace.resolve("Config.txt")).contains("8080"));
    }

    @Test
    void evaluatorRecognizesHiddenContentAndTrueMavenRecoveryOnly() throws Exception {
        Path workspace=temporary.resolve("eval"); Files.createDirectories(workspace); Files.writeString(workspace.resolve("x.txt"),"new");
        V12Task task=task("eval",List.of("修改 x.txt"),Map.of("x.txt","old"),V12Evaluator.FILE_CONTENT);
        List<AgentStep> steps=List.of(toolStep(1,"run_maven_test",ToolResult.failure(ToolErrorCode.TEST_FAILED,"failed")),
                toolStep(2,"read_file",ToolResult.success("new")),toolStep(3,"run_maven_test",ToolResult.success("BUILD SUCCESS")));
        V12TurnResult turn=new V12TurnResult(1,"x",CliMode.CODE,CliMode.CODE,null,null,trajectory(steps));
        V12EvaluationCheck check=new V12EvaluationCheck("eval",List.of(CliMode.CODE),List.of("run_maven_test"),List.of(),List.of(),
                List.of(new V12FileAssertion("x.txt",true,List.of("new"),List.of())),true,true,true,true);
        assertTrue(new V12DeterministicEvaluator().evaluate(task,workspace,List.of(turn),check).passed());
        V12EvaluationCheck impossible=new V12EvaluationCheck("eval",List.of(CliMode.CODE),List.of(),List.of(),List.of(),List.of(),true,true,true,true);
        V12TurnResult directPass=new V12TurnResult(1,"x",CliMode.CODE,CliMode.CODE,null,null,trajectory(List.of(toolStep(1,"run_maven_test",ToolResult.success("ok")))));
        assertFalse(new V12DeterministicEvaluator().evaluate(task,workspace,List.of(directPass),impossible).passed());
    }

    @Test
    void providerBudgetsAreHardAndDoNotDelegatePastCap() throws Exception {
        int[] delegated={0}; LLMClient delegate=messages->{delegated[0]++; return finalAnswer("x");};
        ProviderBudget budget=new ProviderBudget(2); BudgetedLlmClient client=new BudgetedLlmClient(delegate,budget,1);
        client.chat(List.of());
        assertThrows(java.io.IOException.class,()->client.chat(List.of()));
        assertEquals(1,delegated[0]); assertEquals(1,budget.used());
    }

    @Test
    void workspaceResetAndFailureResultDoNotPolluteNextTaskAndReportsAreWritten() throws Exception {
        V12FixtureWorkspace manager=new V12FixtureWorkspace();
        V12Task first=task("first",List.of("x"),Map.of("a.txt","one"),V12Evaluator.FILE_CONTENT);
        V12Task second=task("second",List.of("x"),Map.of("b.txt","two"),V12Evaluator.FILE_CONTENT);
        Path firstSpace=manager.reset(first,temporary.resolve("runs"),"r"); Files.writeString(firstSpace.resolve("bad.txt"),"bad");
        Path secondSpace=manager.reset(second,temporary.resolve("runs"),"r");
        assertFalse(Files.exists(secondSpace.resolve("bad.txt"))); assertTrue(Files.exists(secondSpace.resolve("b.txt")));
        V12TaskResult failed=new V12TaskResult("first",V12Split.DEV,"x",V12Evaluator.FILE_CONTENT,"v1.2","x","fake","now",false,
                "[FINAL_ANSWER]",false,"FAIL",List.of(),Map.of(),V12FailureCategory.UNKNOWN,V12FailureCategory.UNKNOWN,V12FailureOwner.UNKNOWN,"evidence");
        Path report=temporary.resolve("report"); new V12ResultWriter().write(report,List.of(failed),Map.of("success",0));
        assertTrue(Files.exists(report.resolve("run-summary.json"))); assertTrue(Files.exists(report.resolve("task-results.jsonl")));
        assertTrue(Files.readString(report.resolve("failure-report.md")).contains("Manual External Observation"));
    }

    @Test
    void testSplitRequiresExplicitConfirmationBeforeAnyProviderCall() {
        assertThrows(IllegalArgumentException.class,()->V12BenchmarkMain.main(new String[]{"--split=test","--provider=fake"}));
    }

    @Test
    void runnerIsolatesTaskFailureAndContinuesNextTask() throws Exception {
        V12Task first=task("runner-first",List.of("Java 是什么？"),Map.of("a.txt","a"),V12Evaluator.ROUTE_AND_COMPLETION);
        V12Task second=task("runner-second",List.of("Java 是什么？"),Map.of("b.txt","b"),V12Evaluator.ROUTE_AND_COMPLETION);
        V12EvaluationCheck firstCheck=new V12EvaluationCheck(first.id(),List.of(CliMode.CHAT),List.of(),List.of(),List.of(),List.of(),true,false,false,false);
        V12EvaluationCheck secondCheck=new V12EvaluationCheck(second.id(),List.of(CliMode.CHAT),List.of(),List.of(),List.of(),List.of(),true,false,false,false);
        List<V12TaskResult> results=new V12BenchmarkRunner().run("isolation",List.of(first,second),temporary.resolve("out"),
                Map.of(first.id(),firstCheck,second.id(),secondCheck),"fake","test",new ProviderBudget(4),
                task->task.id().equals(first.id()) ? messages->{throw new java.io.IOException("provider failed");}
                        : scripted(finalAnswer("ok")));
        assertEquals(2,results.size());
        assertFalse(results.get(0).success());
        assertTrue(results.get(1).success());
    }

    @Test
    void hiddenMavenAssetsAreInjectedOnlyForEvaluationAndThenRemoved() throws Exception {
        Path workspace=temporary.resolve("hidden-workspace");
        Files.createDirectories(workspace.resolve("src/main/java/demo"));
        Files.writeString(workspace.resolve("src/main/java/demo/Greeter.java"),
                "package demo; public class Greeter { public String greet(){ return \"hello\"; } }");
        Files.writeString(workspace.resolve("pom.xml"),"original fixture pom");
        V12HiddenMavenVerifier.Result result=new V12HiddenMavenVerifier(
                Path.of("benchmark/v1.2/evaluator/hidden"),Path.of(".m2/repository"))
                .verify("dev_10_java_maven_recovery",workspace);
        assertTrue(result.available());
        assertTrue(result.passed(),result.diagnosticSummary());
        assertEquals("original fixture pom",Files.readString(workspace.resolve("pom.xml")));
        assertFalse(Files.exists(workspace.resolve("src/test/java/demo/GreeterHiddenTest.java")));
    }

    @Test
    void dev03CorrectRepairSatisfiesJavaVerificationGuardAndEvaluator() throws Exception {
        V12BenchmarkSuite suite=new V12BenchmarkTaskLoader().load(Path.of("benchmark/v1.2/manifest.json"));
        V12Task task=suite.tasks().stream().filter(t->t.id().equals("dev_03_auto_code")).findFirst().orElseThrow();
        Path workspace=new V12FixtureWorkspace().reset(task,temporary.resolve("runs"),"dev03-offline");
        V12RuntimeHarness runtime=new V12RuntimeHarness(scripted(
                call("read_file",Map.of("path","src/main/java/bench/Calculator.java")),
                call("apply_patch",Map.of("path","src/main/java/bench/Calculator.java","oldText","return a-b","newText","return a+b")),
                call("read_file",Map.of("path","src/main/java/bench/Calculator.java")),
                call("run_maven_test",Map.of()), finalAnswer("done")),workspace);
        List<V12TurnResult> turns=runtime.run(task);
        assertTrue(turns.get(0).trajectory().completed(),turns.get(0).trajectory().finalAnswer());
        AgentStep maven=toolSteps(turns).stream().filter(s->"run_maven_test".equals(s.toolName())).findFirst().orElseThrow();
        assertTrue(maven.toolResult().success(),maven.toolResult().errorMessage());
        V12EvaluationCheck check=new V12EvaluationCheckLoader().load(Path.of("benchmark/v1.2/evaluator/checks.json")).get(task.id());
        assertTrue(new V12DeterministicEvaluator().evaluate(task,workspace,turns,check).passed());
    }

    @Test
    void scriptedDev10RecoversWithAutomaticRereadAndPassesHiddenEvaluation() throws Exception {
        V12BenchmarkSuite suite=new V12BenchmarkTaskLoader().load(Path.of("benchmark/v1.2/manifest.json"));
        V12Task task=suite.tasks().stream().filter(t->t.id().equals("dev_10_java_maven_recovery")).findFirst().orElseThrow();
        Path workspace=new V12FixtureWorkspace().reset(task,temporary.resolve("runs"),"dev10-scripted");
        V12RuntimeHarness runtime=new V12RuntimeHarness(scripted(
                call("run_maven_test",Map.of()),
                call("apply_patch",Map.of("path","src/main/java/demo/Greeter.java",
                        "oldText","return \"hi\";","newText","return \"hello\";")),
                call("run_maven_test",Map.of()),finalAnswer("recovered")),workspace);
        List<V12TurnResult> turns=runtime.run(task);

        assertTrue(turns.get(0).trajectory().completed());
        assertEquals(1,turns.get(0).trajectory().steps().stream()
                .filter(s->s.actionType()==AgentActionType.AUTO_REREAD).count());
        V12EvaluationCheck check=new V12EvaluationCheckLoader().load(Path.of("benchmark/v1.2/evaluator/checks.json")).get(task.id());
        V12HiddenMavenVerifier hidden=new V12HiddenMavenVerifier(
                Path.of("benchmark/v1.2/evaluator/hidden"),Path.of(".m2/repository"));
        V12EvaluationResult evaluation=new V12DeterministicEvaluator(hidden).evaluate(task,workspace,turns,check);
        assertTrue(evaluation.passed(),evaluation.failedCriteria().toString());
    }

    @Test
    void dev03IncorrectRepairFailsDeterministicEvaluator() throws Exception {
        V12BenchmarkSuite suite=new V12BenchmarkTaskLoader().load(Path.of("benchmark/v1.2/manifest.json"));
        V12Task task=suite.tasks().stream().filter(t->t.id().equals("dev_03_auto_code")).findFirst().orElseThrow();
        Path workspace=new V12FixtureWorkspace().reset(task,temporary.resolve("runs"),"dev03-wrong");
        List<AgentStep> steps=List.of(
                toolStep(1,"read_file",ToolResult.success("source")),
                toolStepWithPath(2,"apply_patch","src/main/java/bench/Calculator.java",ToolResult.success("changed")),
                toolStepWithPath(3,"read_file","src/main/java/bench/Calculator.java",ToolResult.success("still wrong")),
                toolStep(4,"run_maven_test",ToolResult.success("BUILD SUCCESS")));
        V12TurnResult turn=new V12TurnResult(1,"x",CliMode.CODE,CliMode.CODE,null,null,trajectory(steps));
        V12EvaluationCheck check=new V12EvaluationCheckLoader().load(Path.of("benchmark/v1.2/evaluator/checks.json")).get(task.id());
        assertFalse(new V12DeterministicEvaluator().evaluate(task,workspace,List.of(turn),check).passed());
    }

    @Test
    void classifiesPrematureFinalFromStructuredJavaVerificationGuard() {
        String path="src/main/java/bench/Calculator.java";
        List<AgentStep> steps=List.of(
                toolStepWithPath(1,"apply_patch",path,ToolResult.success("changed")),
                toolStepWithPath(2,"read_file",path,ToolResult.success("fresh")),
                new AgentStep(3,AgentActionType.RUNTIME_FEEDBACK,null,null,null,Map.of(),null,"done",
                        "JAVA_VERIFICATION_GUARD: run Maven",0,0));
        AgentTrajectory trajectory=new AgentTrajectory("id","task",steps,"done",TerminationReason.MAX_STEPS,false,null,0,1);
        V12TurnResult turn=new V12TurnResult(1,"x",CliMode.CODE,CliMode.CODE,null,null,trajectory);
        V12FailureDiagnosis.Diagnosis diagnosis=V12FailureDiagnosis.classify(List.of(turn),
                new V12EvaluationResult(false,List.of("final Maven pass absent"),Map.of()));
        assertEquals(V12FailureCategory.PREMATURE_FINAL,diagnosis.first());
        assertEquals(V12FailureCategory.MAX_STEP_TERMINATION,diagnosis.last());
        assertEquals(V12FailureOwner.AGENT_POLICY,diagnosis.owner());
    }

    @Test
    void classifiesLegacyPostMutationRereadGuardAsPrematureFinal() {
        String path="src/main/java/demo/Greeter.java";
        List<AgentStep> steps=List.of(
                toolStepWithPath(1,"apply_patch",path,ToolResult.success("changed")),
                new AgentStep(2,AgentActionType.RUNTIME_FEEDBACK,null,null,null,Map.of(),null,"done",
                        "POST_MUTATION_READ_GUARD: reread required",0,0),
                new AgentStep(3,AgentActionType.RUNTIME_FEEDBACK,null,null,null,Map.of(),null,"done",
                        "POST_MUTATION_READ_FAILURE: reread missing",0,0));
        V12TurnResult turn=new V12TurnResult(1,"x",CliMode.CODE,CliMode.CODE,null,null,
                new AgentTrajectory("id","task",steps,"failed",TerminationReason.FINAL_ANSWER,false,null,0,1));
        V12FailureDiagnosis.Diagnosis diagnosis=V12FailureDiagnosis.classify(List.of(turn),
                new V12EvaluationResult(false,List.of("post-mutation reread absent"),Map.of()));
        assertEquals(V12FailureCategory.PREMATURE_FINAL,diagnosis.first());
        assertEquals(V12FailureOwner.AGENT_POLICY,diagnosis.owner());
    }

    @Test
    void javaMutationContractAcceptsEitherPostMutationEvidenceOrder() throws Exception {
        String path="src/main/java/bench/Calculator.java";
        assertTrue(evaluateJavaContract(List.of(
                toolStepWithPath(1,"read_file",path,ToolResult.success("old")),
                toolStepWithPath(2,"apply_patch",path,ToolResult.success("changed")),
                toolStepWithPath(3,"read_file",path,ToolResult.success("fresh")),
                toolStep(4,"run_maven_test",ToolResult.success("BUILD SUCCESS")), finalStep(5))).passed());
        assertTrue(evaluateJavaContract(List.of(
                toolStepWithPath(1,"read_file",path,ToolResult.success("old")),
                toolStepWithPath(2,"apply_patch",path,ToolResult.success("changed")),
                toolStep(3,"run_maven_test",ToolResult.success("BUILD SUCCESS")),
                toolStepWithPath(4,"read_file",path,ToolResult.success("fresh")), finalStep(5))).passed());
    }

    @Test
    void javaMutationContractRejectsMissingOrStaleEvidence() throws Exception {
        String path="src/main/java/bench/Calculator.java";
        assertFalse(evaluateJavaContract(List.of(
                toolStepWithPath(1,"read_file",path,ToolResult.success("old")),
                toolStepWithPath(2,"apply_patch",path,ToolResult.success("changed")), finalStep(3))).passed());
        assertFalse(evaluateJavaContract(List.of(
                toolStepWithPath(1,"read_file",path,ToolResult.success("old")),
                toolStepWithPath(2,"apply_patch",path,ToolResult.success("changed")),
                toolStepWithPath(3,"read_file",path,ToolResult.success("fresh")), finalStep(4))).passed());
        assertFalse(evaluateJavaContract(List.of(
                toolStepWithPath(1,"read_file",path,ToolResult.success("old")),
                toolStepWithPath(2,"apply_patch",path,ToolResult.success("changed")),
                toolStep(3,"run_maven_test",ToolResult.success("BUILD SUCCESS")), finalStep(4))).passed());
        assertFalse(evaluateJavaContract(List.of(
                toolStepWithPath(1,"read_file",path,ToolResult.success("old")),
                toolStep(2,"run_maven_test",ToolResult.success("BUILD SUCCESS")),
                toolStepWithPath(3,"apply_patch",path,ToolResult.success("changed")),
                toolStepWithPath(4,"read_file",path,ToolResult.success("fresh")), finalStep(5))).passed());
    }

    @Test
    void validJavaTrajectoryBlamedOnOldOrderRuleIsEvaluatorFailure() {
        String path="src/main/java/bench/Calculator.java";
        List<AgentStep> steps=List.of(
                toolStepWithPath(1,"read_file",path,ToolResult.success("old")),
                toolStepWithPath(2,"apply_patch",path,ToolResult.success("changed")),
                toolStep(3,"run_maven_test",ToolResult.success("BUILD SUCCESS")),
                toolStepWithPath(4,"read_file",path,ToolResult.success("fresh")), finalStep(5));
        V12TurnResult turn=new V12TurnResult(1,"x",CliMode.CODE,CliMode.CODE,null,null,trajectory(steps));
        V12FailureDiagnosis.Diagnosis diagnosis=V12FailureDiagnosis.classify(List.of(turn),
                new V12EvaluationResult(false,List.of("tool order [read_file, apply_patch, run_maven_test, read_file]"),Map.of()));
        assertEquals(V12FailureCategory.EVALUATOR_FAILURE,diagnosis.first());
        assertEquals(V12FailureCategory.EVALUATOR_FAILURE,diagnosis.last());
        assertEquals(V12FailureOwner.EVALUATOR,diagnosis.owner());
    }

    private static V12Task task(String id,List<String> instructions,Map<String,String> fixture,V12Evaluator evaluator) {
        return new V12Task(id,V12Split.DEV,"test",fixture,instructions,null,List.of("expected"),evaluator,10,List.of("test"),List.of("pass"));
    }
    private static LLMClient scripted(LLMResponse... responses) { Deque<LLMResponse> queue=new ArrayDeque<>(List.of(responses)); return messages->queue.removeFirst(); }
    private static LLMResponse finalAnswer(String text){return new LLMResponse(text,List.of());}
    private static LLMResponse call(String name,Map<String,Object> args) throws Exception {return new LLMResponse("",List.of(new ToolCall(name+"-id",name,new ObjectMapper().writeValueAsString(args))));}
    private static List<AgentStep> toolSteps(List<V12TurnResult> turns){return turns.stream().flatMap(t->t.trajectory().steps().stream()).filter(s->s.actionType()==AgentActionType.TOOL_CALL).toList();}
    private static AgentStep toolStep(int index,String name,ToolResult result){return new AgentStep(index,AgentActionType.TOOL_CALL,name,"id","{}",Map.of(),result,null,null,0,0);}
    private static AgentStep toolStepWithPath(int index,String name,String path,ToolResult result){return new AgentStep(index,AgentActionType.TOOL_CALL,name,"id","{}",Map.of("path",path),result,null,null,0,0);}
    private static AgentStep finalStep(int index){return new AgentStep(index,AgentActionType.FINAL_ANSWER,null,null,null,Map.of(),null,"done",null,0,0);}
    private V12EvaluationResult evaluateJavaContract(List<AgentStep> steps) throws Exception {
        V12Task task=task("java-contract",List.of("fix"),Map.of("pom.xml","<project/>","src/main/java/bench/Calculator.java","old"),V12Evaluator.HIDDEN_FILE_CONTENT);
        V12EvaluationCheck check=new V12EvaluationCheck(task.id(),List.of(CliMode.CODE),List.of(),List.of(),List.of(),List.of(),true,false,true,false);
        V12TurnResult turn=new V12TurnResult(1,"fix",CliMode.CODE,CliMode.CODE,null,null,trajectory(steps));
        return new V12DeterministicEvaluator().evaluate(task,temporary,List.of(turn),check);
    }
    private static AgentTrajectory trajectory(List<AgentStep> steps){return new AgentTrajectory("id","task",steps,"done",TerminationReason.FINAL_ANSWER,true,null,0,1);}
}
