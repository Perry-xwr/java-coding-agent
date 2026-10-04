package com.agent.agent;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.Tool;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolRegistry;
import com.agent.tool.ToolResult;
import com.agent.tool.execution.ProcessExecutionResult;
import com.agent.tool.execution.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicPostMutationRereadTest {
    @TempDir Path workspace;

    @Test
    void applyPatchAutoRereadSatisfiesProgressHistoryAndTrajectory() throws Exception {
        Files.writeString(workspace.resolve("note.txt"),"old");
        Agent agent=agent(responses(
                call("patch","apply_patch","{\"path\":\"note.txt\",\"oldText\":\"old\",\"newText\":\"new\"}"),
                answer("done")),List.of());
        AgentRunResult result=agent.runWithTrajectory("update note.txt");

        assertTrue(result.trajectory().completed());
        AgentStep reread=autoRereads(result).get(0);
        assertEquals("note.txt",reread.arguments().get("path"));
        assertTrue(reread.toolResult().success());
        assertEquals("new",reread.toolResult().output());
        assertEquals(1,feedbackCount(result,"POST_EDIT_CONVERGENCE:"));
        assertTrue(agent.history().stream().anyMatch(m->m.role().equals("system")
                && m.content().startsWith("RUNTIME_AUTO_REREAD:") && m.content().contains("new")));
    }

    @Test
    void insertBeforeInsertAfterAndCreateFileEachAutoReread() throws Exception {
        Files.writeString(workspace.resolve("before.txt"),"anchor");
        AgentRunResult before=agent(responses(
                call("before","insert_before","{\"path\":\"before.txt\",\"anchor\":\"anchor\",\"content\":\"prefix\\n\"}"),
                answer("done")),List.of()).runWithTrajectory("insert before");
        Files.writeString(workspace.resolve("after.txt"),"anchor");
        AgentRunResult after=agent(responses(
                call("after","insert_after","{\"path\":\"after.txt\",\"anchor\":\"anchor\",\"content\":\"\\nsuffix\"}"),
                answer("done")),List.of()).runWithTrajectory("insert after");
        AgentRunResult create=agent(responses(
                call("create","create_file","{\"path\":\"created.txt\",\"content\":\"created\"}"),
                answer("done")),List.of()).runWithTrajectory("create file");

        assertEquals(1,autoRereads(before).size());
        assertEquals(1,autoRereads(after).size());
        assertEquals(1,autoRereads(create).size());
        assertTrue(autoRereads(before).get(0).toolResult().output().contains("prefix"));
        assertTrue(autoRereads(after).get(0).toolResult().output().contains("suffix"));
        assertEquals("created",autoRereads(create).get(0).toolResult().output());
    }

    @Test
    void failedMutationDoesNotAutoReread() throws Exception {
        Files.writeString(workspace.resolve("note.txt"),"actual");
        List<LLMResponse> scripted = new java.util.ArrayList<>();
        scripted.add(new LLMResponse("", List.of(call("patch","apply_patch",
                "{\"path\":\"note.txt\",\"oldText\":\"missing\",\"newText\":\"new\"}"))));
        scripted.addAll(java.util.Collections.nCopies(9, answer("done")));
        AgentRunResult result=agent(scripted,List.of()).runWithTrajectory("update note.txt");
        assertTrue(autoRereads(result).isEmpty());
        assertFalse(result.trajectory().completed());
        assertEquals(TerminationReason.MAX_STEPS, result.trajectory().terminationReason());
    }

    @Test
    void autoRereadFailureCannotSilentlyComplete() {
        ToolRegistry registry=new ToolRegistry();
        registry.register(tool("apply_patch",ToolResult.success("changed",Map.of("changed",true))));
        registry.register(tool("read_file",ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_ERROR,"read failed")));
        Agent agent=new Agent(fake(responses(
                call("patch","apply_patch","{\"path\":\"note.txt\"}"),
                answer("done"),answer("done"))),registry,"test",5,TaskMode.CODE_MODIFICATION,true);

        AgentRunResult result=agent.runWithTrajectory("update note.txt");
        assertFalse(result.trajectory().completed());
        assertEquals(1,autoRereads(result).size());
        assertFalse(autoRereads(result).get(0).toolResult().success());
        assertEquals(1,feedbackCount(result,"AUTO_REREAD_FAILURE:"));
        assertEquals(1,feedbackCount(result,"POST_MUTATION_READ_GUARD:"));
    }

    @Test
    void javaAutoRereadAndPostMutationMavenPassAllowFinal() throws Exception {
        Files.writeString(workspace.resolve("App.java"),"old");
        AgentRunResult result=agent(responses(
                call("patch","apply_patch","{\"path\":\"App.java\",\"oldText\":\"old\",\"newText\":\"new\"}"),
                call("test","run_maven_test","{}"),answer("verified")),List.of(passed()))
                .runWithTrajectory("update App.java");
        assertTrue(result.trajectory().completed());
        assertEquals("verified",result.finalAnswer());
        assertEquals(1,autoRereads(result).size());
    }

    @Test
    void javaAutoRereadWithoutPostMutationMavenStillBlocksFinal() throws Exception {
        Files.writeString(workspace.resolve("App.java"),"old");
        AgentRunResult result=agent(responses(
                call("patch","apply_patch","{\"path\":\"App.java\",\"oldText\":\"old\",\"newText\":\"new\"}"),
                answer("done"),answer("done")),List.of()).runWithTrajectory("update App.java");
        assertFalse(result.trajectory().completed());
        assertEquals(1,feedbackCount(result,"JAVA_VERIFICATION_GUARD:"));
    }

    @Test
    void mavenPassBeforeLatestMutationDoesNotVerifyThatMutation() throws Exception {
        Files.writeString(workspace.resolve("App.java"),"old");
        AgentRunResult result=agent(responses(
                call("test","run_maven_test","{}"),
                call("patch","apply_patch","{\"path\":\"App.java\",\"oldText\":\"old\",\"newText\":\"new\"}"),
                answer("done"),answer("done")),List.of(passed())).runWithTrajectory("update App.java");
        assertFalse(result.trajectory().completed());
        assertEquals(1,feedbackCount(result,"JAVA_VERIFICATION_GUARD:"));
    }

    private Agent agent(List<LLMResponse> responses,List<ProcessExecutionResult> processResults) {
        return new Agent(fake(responses),ToolRegistry.withCliCodingTools(
                workspace,new QueueRunner(processResults),workspace.resolve(".m2/repository")),
                "test",10,TaskMode.CODE_MODIFICATION,true);
    }

    private static List<AgentStep> autoRereads(AgentRunResult result) {
        return result.trajectory().steps().stream()
                .filter(s->s.actionType()==AgentActionType.AUTO_REREAD).toList();
    }

    private static long feedbackCount(AgentRunResult result,String prefix) {
        return result.trajectory().steps().stream()
                .filter(s->s.actionType()==AgentActionType.RUNTIME_FEEDBACK && s.errorMessage()!=null)
                .filter(s->s.errorMessage().startsWith(prefix)).count();
    }

    private static Tool tool(String name,ToolResult result) {
        return new Tool() {
            public String name(){return name;}
            public String description(){return name;}
            public ToolResult execute(String arguments){return result;}
        };
    }

    private static List<LLMResponse> responses(Object... values) {
        return java.util.Arrays.stream(values).map(value->value instanceof LLMResponse response
                ? response : new LLMResponse("",List.of((ToolCall)value))).toList();
    }

    private static ToolCall call(String id,String name,String arguments){return new ToolCall(id,name,arguments);}
    private static LLMResponse answer(String content){return new LLMResponse(content,List.of());}
    private static LLMClient fake(List<LLMResponse> values) {
        ArrayDeque<LLMResponse> queue=new ArrayDeque<>(values);
        return messages->queue.removeFirst();
    }
    private static ProcessExecutionResult passed(){return new ProcessExecutionResult(0,false,"BUILD SUCCESS",false,1);}

    private static final class QueueRunner implements ProcessRunner {
        private final ArrayDeque<ProcessExecutionResult> results;
        private QueueRunner(List<ProcessExecutionResult> values){results=new ArrayDeque<>(values);}
        public ProcessExecutionResult run(List<String> command,Path directory,Duration timeout,int maxOutput){
            return results.removeFirst();
        }
    }
}
