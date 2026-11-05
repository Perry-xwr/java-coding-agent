package com.agent.tui.runtime;

import com.agent.llm.*;
import java.io.IOException;
import java.util.*;

/** Explicit offline Provider, not FakeAgent: Agent chooses/dispatches the returned structured call. */
public final class MockStreamingProvider implements StreamingLlmClient {
    public LLMResponse chat(List<Message> messages)throws IOException{return stream(messages,List.of(),delta->{});}
    public LLMResponse chat(List<Message> messages,List<ToolDefinition> tools)throws IOException{return stream(messages,tools,delta->{});}
    public LLMResponse stream(List<Message> messages,List<ToolDefinition> tools,LlmStreamListener listener)throws IOException{
        String task=messages.stream().filter(m->m.role().equals("user")).reduce((a,b)->b).orElseThrow().content();
        if(task.equals("/error"))throw new IOException("MOCK_PROVIDER_ERROR");
        if(task.equals("/break")){listener.onTextDelta("[MOCK] 未完成片段");throw new IOException("MOCK_STREAM_INTERRUPTED");}
        int lastUser=0;for(int i=0;i<messages.size();i++)if(messages.get(i).role().equals("user"))lastUser=i;
        boolean observed=messages.subList(lastUser+1,messages.size()).stream().anyMatch(m->m.role().equals("tool"));
        int guard=-1,lastObservation=-1;
        for(int i=lastUser+1;i<messages.size();i++){
            Message message=messages.get(i);
            if(message.role().equals("system")&&message.content().startsWith("PREMATURE_FINAL_GUARD:"))guard=i;
            if(message.role().equals("tool"))lastObservation=i;
        }
        boolean canRead=tools.stream().anyMatch(t->t.name().equals("read_file"));
        // The real Core requires fresh evidence AFTER this guard, not an earlier turn's read.
        if(canRead&&guard>lastObservation)
            return new LLMResponse("",List.of(new ToolCall(UUID.randomUUID().toString(),"read_file","{\"path\":\"demo.txt\"}")));
        if(!observed&&tools.stream().anyMatch(t->t.name().equals("read_file"))&&!task.contains("纯聊天"))
            return new LLMResponse("",List.of(new ToolCall(UUID.randomUUID().toString(),"read_file","{\"path\":\"demo.txt\"}")));
        // A provisional final drives the Core's existing ambiguity guard. Do not stream it as
        // a user-facing completed answer: only the post-guard response is displayed.
        if(canRead&&guard<0&&new com.agent.agent.WorkspaceChangeRequirementDetector().detect(task)
                ==com.agent.agent.WorkspaceChangeRequirementDetector.Requirement.UNKNOWN)
            return new LLMResponse("",List.of());
        String text="[MOCK] 中文增量回复。\n已处理当前请求；这是离线 Provider，不代表真实模型能力。";
        for(String chunk:List.of("[MOCK] ","中文增量回复。\n","已处理当前请求；","这是离线 Provider，不代表真实模型能力。")){
            try{Thread.sleep(35);}catch(InterruptedException stop){Thread.currentThread().interrupt();throw new IOException("MOCK_CANCELLED");}
            listener.onTextDelta(chunk);
        }
        return new LLMResponse(text,List.of());
    }
}
