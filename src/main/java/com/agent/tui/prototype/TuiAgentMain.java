package com.agent.tui.prototype;

import com.agent.*;
import com.agent.tui.runtime.*;
import org.jline.terminal.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Independent, hardwired offline host. Never constructs a real network Provider. */
public final class TuiAgentMain {
    public static void main(String[] args)throws Exception{
        try{start(args);}catch(Exception|java.io.IOError failure){
            System.err.println("TUI3_FAILED category="+TuiSessionsMain.failureCategory(failure)+"; no automatic retry");
            if(Boolean.getBoolean("tui2.debug"))TuiSessionsMain.diagnose(failure,System.err);
            throw new java.io.IOException("TUI3_FAILED: offline Agent or session/terminal failure; data retained");
        }
    }
    private static void start(String[] args)throws Exception{
        if(args.length<2||args.length>3||!java.util.List.of("--create","--workspace").contains(args[0])
                ||args.length==3&&!args[2].equals("--headless-demo"))throw new IllegalArgumentException("Use --create/--workspace <isolated path> [--headless-demo]");
        boolean headless=args.length==3;
        if(!headless&&System.console()==null){System.out.println("TUI3_NATIVE_NOT_AVAILABLE; no workspace opened");return;}
        Path workspace=args[0].equals("--create")?TuiSessionWorkspace.create(Path.of(args[1])):TuiSessionWorkspace.writable(Path.of(args[1]));
        if(args[0].equals("--create"))Files.writeString(workspace.resolve("demo.txt"),"离线示例文件：Java 17 + Maven。",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        try(var adapter=new RealAgentAdapter(workspace,new MockStreamingProvider());var controller=new TuiSessionController(workspace,true,adapter)){
            if(headless){
                controller.loop().edit(UiEventLoop.Edit.TEXT,"请读取 demo.txt 并解释内容，不要修改文件。");
                if(controller.submit()==null)throw new java.io.IOException("SUBMISSION_REFUSED");
                long end=System.nanoTime()+10_000_000_000L;
                while(controller.loop().snapshot().current().turn().running()&&System.nanoTime()<end){controller.pump();Thread.sleep(5);}
                controller.pump();
                if(!controller.loop().snapshot().current().turn().status().equals("COMPLETED")||!controller.prepareExit())throw new java.io.IOException("OFFLINE_CORE_DID_NOT_COMPLETE");
                System.out.println("TUI3_MOCK_E2E=PASS; REAL_API_CALLS=0; TOOLS=READ_ONLY; HISTORY_SAVED=TRUE");return;
            }
            try(var terminal=TerminalBuilder.builder().system(true).encoding(StandardCharsets.UTF_8).provider("jni").jni(true).jna(false).jansi(false).dumb(false).build()){
                TuiSessionsMain.run(terminal,controller);
            }
        }
    }
}
