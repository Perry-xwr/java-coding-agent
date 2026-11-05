package com.agent.tui.runtime;

import com.agent.llm.*;
import java.util.*;
import java.io.IOException;

/** Explicit offline tool examples; never a real model capability claim. */
public final class MockApprovalProvider implements StreamingLlmClient {
    private final MockStreamingProvider read=new MockStreamingProvider();
    public LLMResponse chat(List<Message> m)throws IOException{return stream(m,List.of(),d->{});}
    public LLMResponse stream(List<Message> messages,List<ToolDefinition> tools,LlmStreamListener listener)throws IOException{
        int start=0;for(int i=0;i<messages.size();i++)if(messages.get(i).role().equals("user"))start=i;
        String task=messages.get(start).content();boolean observed=messages.subList(start+1,messages.size()).stream().anyMatch(m->m.role().equals("tool"));
        boolean maven=task.toLowerCase(Locale.ROOT).contains("maven")&&task.contains("测试");
        boolean edit=task.contains("demo-note.txt")&&task.contains("创建");
        if(!maven&&!edit)return read.stream(messages,tools,listener);
        if(!observed)return new LLMResponse("",List.of(new ToolCall(UUID.randomUUID().toString(),maven?"run_maven_test":"create_file",maven?"{}":"{\"path\":\"demo-note.txt\",\"content\":\"TUI4B offline\\n\"}")));
        String answer=maven?"[MOCK] 测试请求已处理；批准、执行与测试结果以系统证据为准。":"[MOCK] 文件工具请求已处理；未运行功能测试。";
        listener.onTextDelta(answer.substring(0,7));listener.onTextDelta(answer.substring(7));return new LLMResponse(answer,List.of());
    }
}
