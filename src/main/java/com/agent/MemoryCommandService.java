package com.agent;

import com.agent.agent.AgentEventListener;
import com.agent.memory.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Reuses the CLI's M1–M6 handlers without starting its terminal loop or duplicating Memory rules. */
public final class MemoryCommandService {
    public record Result(CliConversation conversation,String output,boolean persisted) { }
    private final Path workspace;
    private final CliConversationManager manager;
    private final InteractiveCli application;
    private final Capture capture=new Capture();
    private final boolean daily;
    public MemoryCommandService(Path workspace)throws IOException{this(workspace,new OfflineEmbeddingProvider());}
    MemoryCommandService(Path workspace,EmbeddingProvider embeddings)throws IOException{
        this(workspace,embeddings,false);
    }
    static MemoryCommandService forDaily(Path workspace)throws IOException{return new MemoryCommandService(workspace,new OfflineEmbeddingProvider(),true);}
    private MemoryCommandService(Path workspace,EmbeddingProvider embeddings,boolean daily)throws IOException{
        this.daily=daily;this.workspace=daily?DailyWorkspace.check(workspace):TuiSessionWorkspace.writable(workspace);manager=new CliConversationManager(this.workspace);
        var profiles=CliAgentFactory.createProfiles(messages->{throw new IOException("TUI_MEMORY_NO_LLM");},this.workspace,AgentEventListener.NO_OP);
        application=new InteractiveCli(profiles,new ConsoleUi(new PrintStream(capture,true,StandardCharsets.UTF_8)),this.workspace);
        application.localMemoryEmbedding(embeddings);
    }
    public static boolean query(String action){
        return Set.of("status","show","global status","auto status","retrieval status","list","pending").contains(action)
                ||action.startsWith("search ")||action.startsWith("versions ");
    }
    private static boolean supported(String action){
        return Set.of("status","show","compact","global on","global off","global status","auto on","auto off","auto status",
                "harvest","pending","list","remember","search","update","forget","retrieval keyword","retrieval semantic","retrieval hybrid","retrieval status","extractor deterministic").contains(action)
                ||List.of("remember ","search ","update ","forget ","approve ","reject ","review ","replace ","versions ").stream().anyMatch(action::startsWith);
    }
    public Result execute(String sessionId,String action)throws IOException{return perform(sessionId,action,false);}
    public Result afterCompletedTurn(String sessionId)throws IOException{return perform(sessionId,"harvest",true);}
    private Result perform(String sessionId,String action,boolean automatic)throws IOException{
        if(daily)DailyWorkspace.check(workspace);else TuiSessionWorkspace.writable(workspace);var original=manager.load(sessionId);capture.reset();
        if(!supported(action))return new Result(original,"Unsupported local Memory command. LLM extraction/conflict modes are disabled in this offline TUI.",false);
        if(com.agent.llm.LiveTestControl.enabled())return new Result(original,"Memory commands unavailable during controlled live-test mode.",false);
        if(original.snapshot().extraction().llmEnabled()&&(automatic||action.equals("harvest")||action.equals("auto on")))
            return new Result(original,"LLM extraction is disabled here; select /memory extractor deterministic first.",false);
        boolean saved=application.executeLocalMemory(original,action,automatic);
        // Never display optimistic toggle/review messages if the underlying checkpoint failed.
        String output=saved?capture.text():"MEMORY_SESSION_SAVE_FAILED: persisted state retained. Shared changes may already exist; inspect before retry.";
        var updated=manager.load(sessionId);
        return new Result(updated,output,saved);
    }
    private static final class Capture extends OutputStream {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();boolean truncated;
        public void write(int value){if(bytes.size()<32768)bytes.write(value);else truncated=true;}
        void reset(){bytes.reset();truncated=false;}
        String text(){return bytes.toString(StandardCharsets.UTF_8)+(truncated?"\n[Memory output truncated at 32768 UTF-8 bytes; use an exact ID/search]":"");}
    }
}
