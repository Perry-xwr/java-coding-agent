package com.agent;

import com.agent.llm.*;
import com.agent.validation.FinalApiBudget;
import com.agent.tui.runtime.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Restricted final-validation seam; delegates the existing Core, never grants execution authority. */
public final class FinalApiTuiAdapter implements SessionAgentAdapter {
    public static final java.util.List<String> SCENARIOS=java.util.List.of("请用一句话介绍 Java。","请简要解释 Agent 的工作流程。","读取当前测试工作区的 pom.xml，告诉我 Java 版本。");
    private final RealAgentAdapter core;private final FinalApiValidationClient client;private final FinalApiBudget budget;
    private final boolean live;private final Path workspace;private volatile boolean closed;private volatile String lastUsage="UNKNOWN";
    private final java.util.concurrent.atomic.AtomicBoolean resourcesClosed=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.Map<String,String> turnSessions=new java.util.concurrent.ConcurrentHashMap<>();
    public FinalApiTuiAdapter(Path workspace,FinalApiValidationClient client,FinalApiBudget budget,boolean live)throws IOException{
        this(workspace,client,budget,live,new com.agent.tool.execution.DefaultProcessRunner());
    }
    FinalApiTuiAdapter(Path workspace,FinalApiValidationClient client,FinalApiBudget budget,boolean live,com.agent.tool.execution.ProcessRunner runner)throws IOException{
        this.client=Objects.requireNonNull(client);this.budget=Objects.requireNonNull(budget);this.live=live;
        this.workspace=FinalApiWorkspace.check(workspace.toAbsolutePath().normalize().getParent(),live);
        core=new RealAgentAdapter(workspace,client,false,null,runner,workspace.resolve(".m2/repository"));
        if(live){
            client.enforceLiveReadOnly();
            core.observeToolResults((name,result)->{
                if(!result.success()&&java.util.Set.of(com.agent.tool.ToolErrorCode.WORKSPACE_VIOLATION,com.agent.tool.ToolErrorCode.ACCESS_DENIED,
                        com.agent.tool.ToolErrorCode.APPROVAL_DENIED,com.agent.tool.ToolErrorCode.APPROVAL_UNAVAILABLE).contains(result.errorCode()))
                    try{client.denyRuntimePermission(name);}catch(IOException failure){throw new java.io.UncheckedIOException("FINAL_API_PERMISSION_STOP_SAVE_FAILED",failure);}
            });
        }
    }
    public static void validate(CliSessionSnapshot snapshot){
        if(snapshot.activeMode()!=CliMode.READ||snapshot.crossSessionMemory().enabled()||snapshot.extraction().autoEnabled()||snapshot.extraction().llmEnabled()
                ||!snapshot.crossSessionMemory().exposures().isEmpty())throw new IllegalArgumentException("FINAL_API_UNSAFE_SESSION");
    }
    public Mode mode(){return Mode.RESTRICTED_DEEPSEEK;}
    public boolean restrictedValidation(){return true;}
    public boolean fixedValidationPrompts(){return live;}
    public String providerLabel(){return "DeepSeek";}
    public String modelLabel(){return live?"deepseek-flash [LIVE / READ]":"deepseek-flash [MOCK HTTP / READ]";}
    public String capabilities(){return "READ_ONLY; FINAL_API_MAX_4";}
    public String validationStatus(){
        try{var s=budget.state();return providerLabel()+" | "+modelLabel()+"\nHTTP attempts="+s.attempts()+"/4; ledger="+s.status()
                +"; max_tokens=256; request_bytes<=32768\nLast task PROVIDER_REPORTED usage="+lastUsage+"; currency=UNKNOWN\nGlobal/auto/LLM extraction/conflict/Embedding OFF; edits/Maven/processes DISABLED";}
        catch(IOException failure){return "FINAL_API_LEDGER_UNAVAILABLE: stopped; no requests permitted";}
    }
    public boolean available(){return core.available();}
    public boolean canSubmit(){
        if(closed||!available())return false;
        try{var s=budget.state();return s.attempts()<4&&(s.status().equals("PREPARED")||s.status().equals("RUNNING"));}catch(IOException failure){return false;}
    }
    public String submissionRejection(String text){
        if(live&&!SCENARIOS.contains(text))return "LIVE仅限三条固定验收问题；输入未发送（/help）。 [FINAL_API_FIXED_SCENARIOS_ONLY]";
        try{
            FinalApiWorkspace.check(workspace.getParent(),live);
            if(text.equals(SCENARIOS.get(2))&&budget.state().attempts()>2)return "FINAL_API_INSUFFICIENT_BUDGET_FOR_TOOL_SCENARIO: need at least two remaining attempts; do not increase budget";
            for(var session:new CliConversationManager(workspace).list())if(SCENARIOS.contains(text)&&session.snapshot().histories().get(CliMode.READ).stream().anyMatch(m->m.role().equals("user")&&m.content().equals(text)))
                return "FINAL_API_SCENARIO_ALREADY_SUBMITTED: no repeat, restart or extra request";
            return null;
        }catch(IOException failure){closed=true;return "FINAL_API_WORKSPACE_OR_SESSION_UNAVAILABLE: stopped";}
    }
    public void prepare(String id,CliSessionSnapshot snapshot){
        validate(snapshot);try{FinalApiWorkspace.check(workspace.getParent(),live);}catch(IOException unsafe){throw new IllegalArgumentException("FINAL_API_WORKSPACE_CHANGED");}
        core.prepare(id,snapshot);
    }
    public RunHandle start(Request request,EventSink sink){
        if(!canSubmit()||request.memory())throw new IllegalStateException("FINAL_API_STOPPED_OR_UNSAFE");
        var handle=core.start(request,sink);
        turnSessions.put(request.turnId(),request.sessionId());
        return ()->{handle.cancel();try{client.cancelRequests();}catch(IOException failure){closed=true;}};
    }
    public Result takeResult(String turn){
        var result=core.takeResult(turn);
        if(result!=null){var u=result.run().usageSummary();lastUsage=u.usageComplete()?"input="+u.inputTokens()+", output="+u.outputTokens():"UNKNOWN (one or more requests omitted usage)";
            try{
                var evidence=new java.util.LinkedHashMap<String,Object>();evidence.put("turnId",turn);evidence.put("sessionId",turnSessions.remove(turn));evidence.put("transport",live?"LIVE":"MOCK_HTTP");
                evidence.put("completed",result.run().trajectory().completed());evidence.put("termination",result.run().trajectory().terminationReason().name());
                evidence.put("httpLedger",budget.state());evidence.put("providerUsage",u);evidence.put("currencyCost","UNKNOWN");
                evidence.put("stopReason",client.stopReason());
                evidence.put("deniedTool",client.deniedTool());
                Path directory=workspace.getParent().resolve("evidence");
                if(java.nio.file.Files.isSymbolicLink(directory))throw new IOException("UNSAFE_EVIDENCE");
                Path file=directory.resolve("turn-"+java.util.UUID.fromString(turn)+".json");
                try(var output=java.nio.file.Files.newOutputStream(file,java.nio.file.StandardOpenOption.CREATE_NEW)){new com.fasterxml.jackson.databind.ObjectMapper().writeValue(output,evidence);}
            }catch(IOException failure){try{client.cancelRequests();}catch(IOException ignored){ }throw new java.io.UncheckedIOException("FINAL_API_AUDIT_SAVE_FAILED",failure);}
            if(!result.run().trajectory().completed())try{client.cancelRequests();}catch(IOException failure){closed=true;}}
        return result;
    }
    public void close(){
        if(!resourcesClosed.compareAndSet(false,true))return;closed=true;
        try{if(!core.available())client.cancelRequests();}
        catch(IOException failure){throw new java.io.UncheckedIOException("FINAL_API_STOP_SAVE_FAILED",failure);}
        finally{try{client.close();}finally{core.close();}}
    }
}
