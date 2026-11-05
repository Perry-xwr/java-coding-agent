package com.agent.tui.prototype;

import com.agent.*;
import com.agent.tui.runtime.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/** TUI-4 offline host; native UI reuses the existing independent Agent TUI host. */
public final class TuiMemoryMain {
    public static void main(String[] args)throws Exception{
        if(args.length!=3||!args[2].equals("--headless-demo")){TuiAgentMain.main(args);return;}
        try{demo(args);}catch(Exception failure){
            System.err.println("TUI4_DEMO_FAILED; data retained; no real API or automatic retry");
            if(Boolean.getBoolean("tui2.debug"))TuiSessionsMain.diagnose(failure,System.err);
            throw new IOException("TUI4_OFFLINE_MEMORY_DEMO_FAILED");
        }
    }
    private static void require(boolean condition)throws IOException{if(!condition)throw new IOException("Offline Memory gate failed");}
    private static void command(TuiSessionController c,String action)throws IOException{require(c.memoryCommand(action));}
    private static void turn(TuiSessionController c,RealAgentAdapter adapter,String task)throws Exception{
        c.loop().edit(UiEventLoop.Edit.TEXT,task);require(c.submit()!=null);long end=System.nanoTime()+10_000_000_000L;
        do{c.pump();Thread.sleep(5);}while((c.loop().snapshot().current().turn().running()||!adapter.available())&&System.nanoTime()<end);
        c.pump();require(c.loop().snapshot().current().turn().status().equals("COMPLETED"));
    }
    private static void demo(String[] args)throws Exception{
        if(!args[0].equals("--create"))throw new IllegalArgumentException("Headless Memory demo requires a NEW --create workspace");
        Path w=TuiSessionWorkspace.create(Path.of(args[1]));Files.writeString(w.resolve("demo.txt"),"独立离线示例",StandardOpenOption.CREATE_NEW);
        try(var adapter=new RealAgentAdapter(w,new MockStreamingProvider());var c=new TuiSessionController(w,true,adapter)){
            require(c.createSession());require(!c.loop().snapshot().current().memory());command(c,"global on");command(c,"auto on");
            turn(c,adapter,"项目使用 Java 17。请解释这些约定，不要修改文件。");
            String a=c.loop().snapshot().currentSessionId();var manager=new CliConversationManager(w);
            var candidate=manager.load(a).snapshot().extraction().candidates().get(0);command(c,"pending");command(c,"approve "+candidate.candidateId());
            String memory=manager.crossSessionMemory(()->true).listAuthorized().get(0).memoryId();
            require(c.createSession());require(!c.loop().snapshot().current().memory());command(c,"list");command(c,"global on");command(c,"auto on");
            turn(c,adapter,"项目已经升级到 Java 21，以后统一使用 Java 21。请解释约定，不要修改文件。");
            String b=c.loop().snapshot().currentSessionId();var conflict=manager.load(b).snapshot().extraction().candidates().stream().filter(x->x.reviewStatus().equals("PENDING")&&x.candidateContent().contains("21")).findFirst().orElseThrow();
            command(c,"review "+conflict.candidateId());command(c,"replace "+conflict.candidateId()+" "+memory);command(c,"versions "+memory);
            require(manager.crossSessionMemory(()->true).listAuthorized().get(0).content().contains("21"));
            require(c.approvalCommand("demo"));require(c.approvalCommand("deny "+c.loop().snapshot().localApproval().requestId()));
            require(c.approvalAudit().stream().noneMatch(x->x.decision()==com.agent.execution.secure.ApprovalDecision.Status.APPROVED));
            command(c,"forget "+memory);require(manager.crossSessionMemory(()->true).listAuthorized().isEmpty());command(c,"global off");require(c.prepareExit());
        }
        require(new CliConversationManager(w).crossSessionMemory(()->true).listAuthorized().isEmpty());
        System.out.println("TUI4_MEMORY_E2E=PASS; APPROVAL=DENIAL_ONLY; DANGEROUS_TOOLS=DISABLED; REAL_API_CALLS=0");
    }
}
