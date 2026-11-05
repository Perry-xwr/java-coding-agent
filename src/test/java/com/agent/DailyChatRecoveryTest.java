package com.agent;

import com.agent.llm.*;
import com.agent.tool.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyChatRecoveryTest {
    @TempDir Path root;
    final DailyRuntimeTest helper=new DailyRuntimeTest();
    final ObjectMapper json=new ObjectMapper();
    JsonNode request(okhttp3.Request r)throws Exception{var b=new okio.Buffer();r.body().writeTo(b);return json.readTree(b.readUtf8());}
    String lastTool(JsonNode request){String result="";for(var m:request.path("messages"))if(m.path("role").asText().equals("tool"))result=m.path("content").asText();return result;}

    @ParameterizedTest @ValueSource(strings={"那我们先从注意力机制开始讲","从注意力机制开始讲","解释一下 Transformer","总结一下我们刚刚讨论的内容","我们继续学习大模型算法，我们刚刚学到哪里了？"})
    void teachingCompletesInDefaultUnifiedWithoutChangingFiles(String input)throws Exception{
        Path w=Files.createDirectory(root.resolve("teaching"));
        try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{
            var body=request(r);for(var tool:body.path("tools"))assertTrue(Set.of("list_files","find_files","search_code","read_file","search_sessions","read_session_context").contains(tool.path("function").path("name").asText()));
            return helper.answer(r,"注意力机制根据输入计算相关性。没有历史记录时，不能确定之前学到哪里。");
        }catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
            helper.send(c,a,input);assertEquals("UNIFIED",c.loop().snapshot().current().profile());assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());assertEquals(1,client.attempts());
            var h=new CliConversationManager(w).load(c.loop().snapshot().currentSessionId()).snapshot().histories().get(CliMode.CODE);
            assertEquals(1,h.stream().filter(m->m.role().equals("assistant")).count());assertTrue(h.stream().noneMatch(m->m.content().startsWith("PREMATURE_FINAL_GUARD")||m.content().startsWith(RealAgentAdapter.FAILED_TURN_MARKER)));
        }
    }

    @Test void dailySearchEmptyChatThenReadDefaultProfileRestoresOnlySourcedProgress()throws Exception{
        Path w=Files.createDirectory(root.resolve("learning"));var n=new AtomicInteger();var sourceId=new AtomicReference<String>();
        try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{
            var body=request(r);String result=lastTool(body);
            return switch(n.incrementAndGet()){
                case 1->helper.reply(r,helper.call("search_sessions","{\"query\":\"注意力机制学习\"}"));
                case 2->{var hits=json.readTree(result).path("matches");assertEquals(1,hits.size());var hit=hits.get(0);assertEquals(sourceId.get(),hit.path("sessionId").asText());assertEquals("CODE",hit.path("defaultProfile").asText());assertEquals(List.of("CODE"),json.convertValue(hit.path("availableProfiles"),List.class));yield helper.reply(r,helper.call("read_session_context",json.writeValueAsString(Map.of("session_id",sourceId.get(),"profile","CHAT"))));}
                case 3->{var empty=json.readTree(result);assertEquals("NO_MESSAGES_IN_PROFILE",empty.path("status").asText());assertEquals(0,empty.path("messages").size());assertEquals("CODE",empty.path("defaultProfile").asText());yield helper.reply(r,helper.call("read_session_context",json.writeValueAsString(Map.of("session_id",sourceId.get(),"max_chars",6000))));}
                default->{var context=json.readTree(result);assertEquals("CODE",context.path("profile").asText());assertEquals("OK",context.path("status").asText());String quote=context.path("messages").get(0).path("text").asText();assertTrue(quote.contains("下一步学习多头注意力"));assertFalse(context.path("messages").get(0).path("sourceSha256").asText().isBlank());yield helper.answer(r,"根据历史会话 "+context.path("sessionId").asText()+" 的用户记录："+quote+"；不能据此推断后续已经学完。");}
            };
        }catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
            sourceId.set(new SessionHistoryToolsTest().seed(c,w,"注意力机制学习","我们学习了注意力机制的 Q、K、V，下一步学习多头注意力。","目前只讨论过 Q、K、V。"));
            new SessionHistoryToolsTest().seed(c,w,"无关主题","晚饭怎么做？","先烧水。");c.createSession();
            byte[] before=Files.readAllBytes(w.resolve(".agent-cli/conversations/"+sourceId.get()+".json"));
            helper.send(c,a,"我们继续学习大模型算法，我们刚刚学到哪里了？");assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());assertEquals(4,client.attempts());
            assertTrue(c.loop().snapshot().current().turn().tools().stream().allMatch(t->t.status().equals("OK")));assertFalse(c.loop().snapshot().current().memory());
            assertTrue(ConversationCopy.select(new CliConversationManager(w).load(c.loop().snapshot().currentSessionId()).snapshot().histories().get(CliMode.CODE),false).contains(sourceId.get()));
            assertArrayEquals(before,Files.readAllBytes(w.resolve(".agent-cli/conversations/"+sourceId.get()+".json")));
        }
    }

    @Test void noLearningHistoryIsNormalEmptyEvidenceNotInventedProgress()throws Exception{
        Path w=Files.createDirectory(root.resolve("empty"));var n=new AtomicInteger();
        try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{
            if(n.incrementAndGet()==1)return helper.reply(r,helper.call("search_sessions","{\"query\":\"注意力机制学习\"}"));
            var result=json.readTree(lastTool(request(r)));assertEquals(0,result.path("matches").size());assertTrue(result.path("warning").asText().contains("No match means no evidence"));return helper.answer(r,"没有找到学习记录，无法确定上次进度。请提供之前学到的内容。");
        }catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
            helper.send(c,a,"我们继续学习大模型算法，我们刚刚学到哪里了？");assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());assertEquals(2,client.attempts());assertEquals("OK",c.loop().snapshot().current().turn().tools().get(0).status());
        }
    }

    @Test void unpunctuatedCrossSessionPlanRecallCompletesWithoutMutationProof()throws Exception{
        Path w=Files.createDirectory(root.resolve("plan-recall"));var n=new AtomicInteger();var sourceId=new AtomicReference<String>();
        try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{
            var body=request(r);
            for(var tool:body.path("tools"))assertTrue(Set.of("list_files","find_files","search_code","read_file","search_sessions","read_session_context").contains(tool.path("function").path("name").asText()));
            if(n.incrementAndGet()==1)return helper.reply(r,helper.call("search_sessions","{\"query\":\"周日晚上计划\"}"));
            var result=json.readTree(lastTool(body));var matches=result.path("matches");assertEquals(1,matches.size());assertEquals(sourceId.get(),matches.get(0).path("sessionId").asText());
            return helper.answer(r,"根据历史会话记录，你周日晚上安排了打《猫和老鼠》手游；这是历史约定，不代表已实际执行。");
        }catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
            sourceId.set(new SessionHistoryToolsTest().seed(c,w,"周末计划","我们约定周日晚上打《猫和老鼠》手游。","记录了周日晚上打《猫和老鼠》手游。"));
            c.createSession();helper.send(c,a,"按照我之前的计划，我周日晚上要做什么");
            assertEquals("UNIFIED",c.loop().snapshot().current().profile());
            assertEquals("COMPLETED",c.loop().snapshot().current().turn().status(),c.loop().snapshot().notice());
            assertEquals(2,client.attempts());assertEquals(1,c.loop().snapshot().current().turn().tools().size());
            assertEquals("OK",c.loop().snapshot().current().turn().tools().get(0).status());
            assertTrue(c.loop().snapshot().current().history().stream().anyMatch(h->h.text().contains("周日晚上安排了打")));
            assertTrue(c.loop().snapshot().current().history().stream().noneMatch(h->h.text().contains("MUTATION_FAILURE_FINAL")||h.text().contains("PREMATURE_FINAL_GUARD")));
            assertTrue(c.loop().snapshot().current().history().stream().filter(h->h.role().equals("Agent")).count()==1);
        }
    }

    @ParameterizedTest @ValueSource(strings={"minimal","code","zero","integralDecimal","emptyChat","eof"})
    void schemaLegalReadArgumentsThroughDailyHttpAndRegistry(String variant)throws Exception{
        Path w=Files.createDirectory(root.resolve("arguments"));var id=new AtomicReference<String>();var n=new AtomicInteger();
        try(var lease=DailyWorkspace.acquire(w);var client=DailyDeepSeekClient.offline(r->{try{
            var body=request(r);
            if(n.incrementAndGet()==1){
                var schema=json.createObjectNode();for(var tool:body.path("tools"))if(tool.path("function").path("name").asText().equals("read_session_context"))schema=(com.fasterxml.jackson.databind.node.ObjectNode)tool.path("function").path("parameters");
                assertEquals(128,schema.path("properties").path("max_chars").path("minimum").asInt());assertEquals(6000,schema.path("properties").path("max_chars").path("maximum").asInt());
                var args=json.createObjectNode().put("session_id",id.get());switch(variant){case "code"->args.put("profile","CODE").put("max_chars",6000);case "zero"->args.put("message_index",0).put("character_offset",0).put("max_chars",128);case "integralDecimal"->args.put("message_index",1.0).put("max_chars",4000.0);case "emptyChat"->args.put("profile","CHAT");case "eof"->args.put("message_index",3);default->{}}
                return helper.reply(r,helper.call("read_session_context",args.toString()));
            }
            var result=json.readTree(lastTool(body));assertTrue(result.path("messages").isArray());assertEquals(variant.equals("emptyChat")?"NO_MESSAGES_IN_PROFILE":variant.equals("eof")?"END_OF_HISTORY":"OK",result.path("status").asText());
            return helper.answer(r,result.path("messages").isEmpty()?"该页没有讨论记录，不能推断进度。":"根据引用记录继续说明，未修改文件。");
        }catch(Exception e){throw new java.io.IOException(e);}});var a=new DailyAgentAdapter(lease,client,null);var c=new TuiSessionController(w,true,a)){
            id.set(new SessionHistoryToolsTest().seed(c,w,"学习","注意力机制学习记录。","下一步待确认。"));c.createSession();helper.send(c,a,"查询历史学习记录并说明进度。");assertEquals(2,client.attempts());assertEquals("COMPLETED",c.loop().snapshot().current().turn().status());assertEquals("OK",c.loop().snapshot().current().turn().tools().get(0).status());
        }
    }

    @Test void readArgumentsMissingStorageAndEmptyProfilesHaveDistinctResults()throws Exception{
        Path w=Files.createDirectory(root.resolve("errors"));var registry=new ToolRegistry();SessionHistoryTools.register(registry,w,UUID.randomUUID().toString());String missing=UUID.randomUUID().toString();
        assertEquals(ToolErrorCode.FILE_NOT_FOUND,registry.execute("read_session_context",json.writeValueAsString(Map.of("session_id",missing))).errorCode());
        for(var bad:List.of(Map.of("session_id",missing,"max_chars",20000),Map.of("session_id",missing,"max_chars",127),Map.of("session_id",missing,"message_index",-1),Map.of("session_id",missing,"profile","UNIFIED"),Map.of("session_id",missing,"max_chars","4000"),Map.of("session_id",missing,"message_index",1.5),Map.of("session_id","../outside"))){var result=registry.execute("read_session_context",json.writeValueAsString(bad));assertEquals(ToolErrorCode.INVALID_ARGUMENTS,result.errorCode());assertTrue(result.errorMessage().contains("128..6000"));}
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS,registry.execute("read_session_context","invalid JSON").errorCode());
        Files.createDirectories(w.resolve(".agent-cli/conversations"));Files.writeString(w.resolve(".agent-cli/conversations/"+missing+".json"),"broken");assertEquals(ToolErrorCode.TOOL_EXECUTION_ERROR,registry.execute("read_session_context",json.writeValueAsString(Map.of("session_id",missing))).errorCode());
        // Valid JSON with invalid stored model data is storage failure too, not caller arguments.
        Files.writeString(w.resolve(".agent-cli/conversations/"+missing+".json"),"{\"version\":999}");assertEquals(ToolErrorCode.TOOL_EXECUTION_ERROR,registry.execute("read_session_context",json.writeValueAsString(Map.of("session_id",missing))).errorCode());
    }
}
