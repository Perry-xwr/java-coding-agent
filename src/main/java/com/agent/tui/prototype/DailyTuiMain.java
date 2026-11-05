package com.agent.tui.prototype;

import com.agent.*;
import com.agent.llm.*;
import com.agent.execution.secure.TuiMavenApprovalChannel;
import java.nio.file.Path;
import java.io.IOException;

/** Formal Daily entry. No startup request, Mock fallback, FINAL-API confirmation or API quota. */
public final class DailyTuiMain {
    public static Path workspace(String[] args){
        if(args.length==0)return Path.of("").toAbsolutePath().normalize();
        if(args.length==2&&args[0].equals("--workspace"))return Path.of(args[1]).toAbsolutePath().normalize();
        throw new IllegalArgumentException("Use --workspace <project-directory>");
    }
    static String runtimeIdentity()throws Exception{
        var type=TuiFrameRenderer.class;
        byte[] bytes;try(var in=type.getResourceAsStream("TuiFrameRenderer.class")){if(in==null)throw new IOException("RENDERER_ID_UNAVAILABLE");bytes=in.readAllBytes();}
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        byte[] input;try(var in=TuiSessionsMain.class.getResourceAsStream("TuiSessionsMain.class")){if(in==null)throw new IOException("INPUT_ID_UNAVAILABLE");input=in.readAllBytes();}
        String inputHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));
        return "DAILY_RUNTIME UX1-ROOT | renderer="+type.getProtectionDomain().getCodeSource().getLocation()+" | SHA256="+hash+" | input_SHA256="+inputHash+" | mouseDebug="+Boolean.getBoolean("agent.tui.mouseDebug");
    }
    public static void main(String[] args)throws Exception{
        try{
            Path selected=workspace(args);String key=System.getenv("DEEPSEEK_API_KEY");
            if(key==null||key.isBlank())throw new IOException("DEEPSEEK_API_KEY_MISSING");
            if(LiveTestControl.enabled())throw new IOException("DAILY_REFUSES_EXPERIMENT_LIMITS");
            try(var terminal=TuiSessionsMain.openNativeTerminal();var lease=DailyWorkspace.acquire(selected);
                var approvals=TuiMavenApprovalChannel.openNative(terminal);var client=DailyDeepSeekClient.configured(key);
                var adapter=new DailyAgentAdapter(lease,client,approvals);var controller=new TuiSessionController(lease.root(),true,adapter)){
                try{controller.enableGlobalRegistry(GlobalWorkspaceRegistry.userDefault());}catch(IOException|RuntimeException unavailable){controller.loop().notice("GLOBAL_CATALOG_UNAVAILABLE: local sessions remain usable");}
                controller.enableMemoryConsent(MemoryConsentStore.forWorkspace(lease.root()));
                controller.loop().localOutput(runtimeIdentity()+"\nTerminal backend="+terminal.getClass().getName()+" / "+terminal.getType()+"; native paste status appears in the TUI notice after raw-mode initialization.\nWorkspace: "+lease.root()+"\nDAILY DeepSeek / deepseek-flash; no startup request or API quota. /status for usage.\nMaven requires exact R1 approval. Do not open the old CLI on this workspace simultaneously.");
                TuiSessionsMain.run(terminal,controller);
            }
        }catch(Exception failure){
            String code=failure.getMessage()!=null&&failure.getMessage().matches("[A-Z0-9_]{1,80}")?failure.getMessage():failure.getClass().getSimpleName();
            System.err.println("DAILY_START_OR_RUNTIME_FAILED: "+code+"; data retained; no automatic retry");throw new IOException("DAILY_FAILED");
        }
    }
}
