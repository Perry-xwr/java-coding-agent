package com.agent;

import com.agent.tool.ToolResult;
import java.util.*;

/** UI projection only. Never includes raw tool bodies, file snapshots, arguments or HTTP headers. */
public final class TuiToolPresentation {
    private TuiToolPresentation() { }
    private static String safe(Object value,int max){
        String text=TuiSessionController.redactForDisplay(Objects.toString(value,"UNKNOWN"));
        return text.length()<=max?text:text.substring(0,max)+" [truncated]";
    }
    public static String summary(ToolResult result){
        String base=result.success()?"OK":result.errorCode().name();
        Object memoryDecision=result.metadata().get("memoryDecision");
        if(memoryDecision instanceof String text&&text.matches("[A-Z_]{1,48}"))base=text;
        Object status=result.metadata().get("testStatus");
        if(status instanceof String text&&text.matches("[A-Z_]{1,48}"))base=text;
        if(result.metadata().get("testsRun") instanceof Number count)base+=" tests="+count;
        return safe(base,160);
    }
    public static String details(ToolResult result){
        var lines=new ArrayList<String>();lines.add("Result: "+(result.success()?"SUCCESS":result.errorCode()));
        for(String key:List.of("approvalStatus","executed","testStatus","exitCode","timedOut","outputTruncated","testSummaryAvailable","testsRun","testsPassed","testsFailed","testsErrors","testsSkipped","diagnosticType"))
            if(result.metadata().containsKey(key))lines.add(key+"="+safe(result.metadata().get(key),120));
        if(result.metadata().get("diagnosticSummary") instanceof String diagnostic)lines.add("Untrusted diagnostic summary: "+safe(diagnostic,700));
        if(result.metadata().get("failedTests") instanceof List<?> failed)lines.add("Failed tests: "+safe(failed.stream().limit(5).toList(),400));
        lines.add("Raw output/file bodies stay folded; these observations never grant permission.");
        return String.join("\n",lines);
    }
}
