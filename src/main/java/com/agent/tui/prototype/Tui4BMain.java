package com.agent.tui.prototype;

import com.agent.*;
import com.agent.tui.runtime.*;
import com.agent.execution.secure.*;
import org.jline.terminal.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Independent Mock-only entry. File policy matches CLI; optional Maven uses native exact R1. */
public final class Tui4BMain {
    public static void main(String[] args)throws Exception{
        try{start(args);}catch(Exception|java.io.IOError failure){
            System.err.println("TUI4B_FAILED: "+TuiSessionsMain.failureCategory(failure)+"; data retained; no automatic retry");
            if(Boolean.getBoolean("tui2.debug"))TuiSessionsMain.diagnose(failure,System.err);
            throw new java.io.IOException("TUI4B_FAILED: session/execution/terminal gate failed");
        }
    }
    private static void start(String[] args)throws Exception{
        if(args.length<2||!List.of("--create","--workspace").contains(args[0]))throw new IllegalArgumentException("Isolated workspace required");
        boolean headless=false;Path repository=null;
        for(int i=2;i<args.length;i++){if(args[i].equals("--headless-demo"))headless=true;
            else if(args[i].equals("--maven")&&i+1<args.length)repository=Path.of(args[++i]).toAbsolutePath().normalize();else throw new IllegalArgumentException("Unknown argument");}
        if(repository!=null&&(headless||System.console()==null)){System.err.println("MAVEN_APPROVAL_UNAVAILABLE: native console required; no headless/stdin approval fallback");throw new IllegalStateException("Native console required for Maven");}
        if(!headless&&System.console()==null){System.out.println("TUI4B_NATIVE_NOT_AVAILABLE; no workspace opened");return;}
        Path w=args[0].equals("--create")?TuiSessionWorkspace.create(Path.of(args[1])):TuiSessionWorkspace.writable(Path.of(args[1]));
        if(args[0].equals("--create")){Files.writeString(w.resolve("demo.txt"),"独立离线示例",StandardOpenOption.CREATE_NEW);if(repository!=null)Tui4BFixture.create(w);}
        if(headless){
            try(var adapter=RealAgentAdapter.forFileEditing(w,new MockApprovalProvider());var c=new TuiSessionController(w,true,adapter)){
                c.loop().edit(UiEventLoop.Edit.TEXT,"请创建 demo-note.txt 文件，内容为 TUI4B offline。");if(c.submit()==null)throw new java.io.IOException("Submission refused");
                long end=System.nanoTime()+8_000_000_000L;while(c.loop().snapshot().current().turn().running()&&System.nanoTime()<end){c.pump();Thread.sleep(5);}c.pump();
                if(!Files.readString(w.resolve("demo-note.txt")).equals("TUI4B offline\n")||!c.loop().snapshot().current().turn().status().equals("COMPLETED")||!c.diffCommand()||!c.prepareExit())throw new java.io.IOException("File edit gate failed");
            }
            System.out.println("TUI4B_FILE_E2E=PASS; MAVEN=DISABLED; REAL_API_CALLS=0");return;
        }
        try(var terminal=TuiSessionsMain.openNativeTerminal()){
            var channel=repository==null?null:TuiMavenApprovalChannel.openNative(terminal);
            try(var adapter=channel==null?RealAgentAdapter.forFileEditing(w,new MockApprovalProvider()):RealAgentAdapter.withMavenApproval(w,new MockApprovalProvider(),channel,repository);
                var c=new TuiSessionController(w,true,adapter)){
                TuiSessionsMain.run(terminal,c);
            }finally{if(channel!=null){channel.close();writeAudit(w,channel);}}
        }
    }
    private static void writeAudit(Path workspace,TuiMavenApprovalChannel channel)throws Exception{
        Path parent=TuiSessionWorkspace.readable(workspace).getParent();Path evidence=parent.resolve("evidence");
        if(Files.isSymbolicLink(evidence))throw new java.io.IOException("Unsafe audit directory");Files.createDirectories(evidence);
        Path temp=Files.createTempFile(evidence,"approval-",".tmp");
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),Map.of("truncated",channel.auditTruncated(),"records",channel.audit()));
        Files.move(temp,evidence.resolve("tui4b-approvals-"+UUID.randomUUID()+".json"),StandardCopyOption.ATOMIC_MOVE);
    }
}
