package com.agent.tui.prototype;

import com.agent.*;
import com.agent.llm.*;
import com.agent.validation.FinalApiBudget;
import org.jline.terminal.*;
import okhttp3.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Independent validation host. Default is offline Mock HTTP; no task is submitted on startup. */
public final class FinalApiTuiMain {
    public record Options(Path root,boolean create,boolean live,boolean enableReal){}
    public static Options options(String[] args){
        Path root=null;boolean create=false,live=false,enable=false;
        for(int i=0;i<args.length;i++)switch(args[i]){
            case "--create","--resume"->{if(root!=null||i+1>=args.length)throw new IllegalArgumentException("One owned run root required");create=args[i].equals("--create");root=Path.of(args[++i]).toAbsolutePath().normalize();}
            case "--provider"->{if(i+1>=args.length)throw new IllegalArgumentException("Provider required");String provider=args[++i];if(!provider.equals("mock")&&!provider.equals("deepseek"))throw new IllegalArgumentException("Only mock/deepseek supported");live=provider.equals("deepseek");}
            case "--enable-real-api"->enable=true;
            default->throw new IllegalArgumentException("Unknown FINAL-API option");
        }
        if(root==null||live!=enable)throw new IllegalArgumentException("Real provider requires both --provider deepseek and --enable-real-api");
        return new Options(root,create,live,enable);
    }
    public static void main(String[] args)throws Exception{
        try{launch(options(args));}catch(Exception failure){
            String reason=failure.getMessage()!=null&&failure.getMessage().matches("[A-Z0-9_]{1,80}")?failure.getMessage():failure.getClass().getSimpleName();
            System.err.println("FINAL_API_TUI_STOPPED: "+reason+"; no retry or cleanup; retain run directory");
            // No raw exception/cause/body/key in output.
            throw new IOException("FINAL_API_TUI_STOPPED");
        }
    }
    private static void launch(Options options)throws Exception{
        if(LiveTestControl.enabled())throw new IOException("EXISTING_LIVE_TEST_CONFIGURATION_NOT_ALLOWED");
        var console=System.console();if(console==null)throw new IOException("NATIVE_CONSOLE_REQUIRED");
        String key=null;
        FinalApiBudget.Preview confirmed=null;
        if(options.live()){
            confirmed=preconfirm(options);
            console.printf("%s%n",confirmationSummary(options,confirmed));
            confirmed.requireAvailable();
            key=System.getenv("DEEPSEEK_API_KEY");if(key==null||key.isBlank())throw new IOException("DEEPSEEK_API_KEY_MISSING");
            String expected="ENABLE DEEPSEEK 4 "+options.root();
            String confirmation=console.readLine("Type exactly: %s%n> ",expected);
            requireConfirmation(options,confirmation);
        }
        Path w=options.create()?FinalApiWorkspace.create(options.root(),options.live()):FinalApiWorkspace.check(options.root(),options.live());
        var budget=options.live()?FinalApiBudget.openConfirmed(confirmed,true):new FinalApiBudget(options.root().resolve("evidence/http-ledger"),true);
        var state=budget.state();
        console.printf("Provider=%s; model=deepseek-flash; HTTP attempts=%d/4; ledger=%s%n",options.live()?"DeepSeek LIVE":"DeepSeek MOCK HTTP (no network)",state.attempts(),state.status());
        // Failed/exhausted runs may be inspected externally; never reconnect or reset their allowance.
        if(state.attempts()>=4||!java.util.Set.of("PREPARED","RUNNING").contains(state.status()))throw new IOException("FINAL_API_BUDGET_STOPPED");
        try(var terminal=TuiSessionsMain.openNativeTerminal();
            var client=options.live()?FinalApiValidationClient.authorized(budget,key,true):new FinalApiValidationClient(budget,FinalApiTuiMain::mock);
            var adapter=new FinalApiTuiAdapter(w,client,budget,options.live());
            var controller=new TuiSessionController(w,true,adapter)){
            TuiSessionsMain.run(terminal,controller);
        }
    }
    static FinalApiBudget.Preview preconfirm(Options options)throws IOException{
        if(options.create()){
            if(Files.exists(options.root(),LinkOption.NOFOLLOW_LINKS))throw new IOException("FINAL_API_RUN_ROOT_ALREADY_EXISTS");
        }else FinalApiWorkspace.check(options.root(),true);
        return FinalApiBudget.inspect(options.root().resolve("evidence/http-ledger"));
    }
    static String confirmationSummary(Options options,FinalApiBudget.Preview preview){
        return "FINAL-API DeepSeek LIVE / deepseek-flash / READ\nWorkspace: "+options.root().resolve("workspace")
                +"\nMemory OFF; auto OFF; LLM extraction/conflict/Embedding OFF\n"+preview.display()
                +"\nHard cap=4 HTTP attempts; max_tokens=256; final request<=32768 UTF-8 bytes\nNo retry/redirect; no edits/Maven/processes. Paid usage may occur.";
    }
    static void requireConfirmation(Options options,String input)throws IOException{
        if(!("ENABLE DEEPSEEK 4 "+options.root()).equals(input))throw new IOException("REAL_API_NOT_CONFIRMED");
    }
    private static Response mock(Request request)throws IOException{
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var bytes=new okio.Buffer();request.body().writeTo(bytes);
        var messages=json.readTree(bytes.readByteArray()).path("messages");String task="";boolean observed=false;
        for(var message:messages){if(message.path("role").asText().equals("user")){task=message.path("content").asText();observed=false;}else if(message.path("role").asText().equals("tool"))observed=true;}
        boolean read=task.equals(FinalApiTuiAdapter.SCENARIOS.get(2));String body;
        if(read&&!observed){
            var function=java.util.Map.of("name","read_file","arguments","{\"path\":\"pom.xml\"}");
            var call=java.util.Map.of("index",0,"id",java.util.UUID.randomUUID().toString(),"function",function);
            body="data: "+json.writeValueAsString(java.util.Map.of("choices",java.util.List.of(java.util.Map.of("delta",java.util.Map.of("tool_calls",java.util.List.of(call)),"finish_reason","tool_calls"))))+"\n\n";
        }else{
            String response=read?"Java 17（MOCK HTTP；实际文件证据来自 read_file）。":"[MOCK HTTP] 离线增量回复，不代表真实模型能力。";
            body="data: "+json.writeValueAsString(java.util.Map.of("choices",java.util.List.of(java.util.Map.of("delta",java.util.Map.of("content",response.substring(0,7))))))+"\n\n"
                    +"data: "+json.writeValueAsString(java.util.Map.of("choices",java.util.List.of(java.util.Map.of("delta",java.util.Map.of("content",response.substring(7)),"finish_reason","stop"))))+"\n\n";
        }
        body+="data: [DONE]\n\n";
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("offline").body(ResponseBody.create(body,MediaType.get("text/event-stream"))).build();
    }
}
