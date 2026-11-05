package com.agent.tool;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Aggregated Surefire/Failsafe counters parsed from actual Maven process output. */
public record MavenTestSummary(boolean available, int total, int passed, int failures,
                               int errors, int skipped) {
    private static final Pattern SUMMARY = Pattern.compile(
            "(?i)Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)"
    );
    private static final Pattern PER_CLASS = Pattern.compile("-+\\s+in\\s+\\S+");

    public static MavenTestSummary parse(String output) {
        Matcher matcher = SUMMARY.matcher(output == null ? "" : output);
        int total = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        boolean found = false;
        int classTotal=0,classFailures=0,classErrors=0,classSkipped=0;
        while (matcher.find()) {
            try {
                int n=Integer.parseInt(matcher.group(1)),f=Integer.parseInt(matcher.group(2)),e=Integer.parseInt(matcher.group(3)),s=Integer.parseInt(matcher.group(4));
                if((long)f+e+s>n)return new MavenTestSummary(false,0,0,0,0,0);
                int end=output.indexOf('\n',matcher.end());String tail=output.substring(matcher.end(),end<0?output.length():end);
                if(PER_CLASS.matcher(tail).find()){
                    classTotal=Math.addExact(classTotal,n);classFailures=Math.addExact(classFailures,f);classErrors=Math.addExact(classErrors,e);classSkipped=Math.addExact(classSkipped,s);
                }else{
                    // A module aggregate already includes the preceding per-class lines.
                    total=Math.addExact(total,n);failures=Math.addExact(failures,f);errors=Math.addExact(errors,e);skipped=Math.addExact(skipped,s);
                    classTotal=classFailures=classErrors=classSkipped=0;
                }
                found = true;
            } catch (ArithmeticException | NumberFormatException invalidCount) {
                return new MavenTestSummary(false, 0, 0, 0, 0, 0);
            }
        }
        try{total=Math.addExact(total,classTotal);failures=Math.addExact(failures,classFailures);errors=Math.addExact(errors,classErrors);skipped=Math.addExact(skipped,classSkipped);}
        catch(ArithmeticException overflow){return new MavenTestSummary(false,0,0,0,0,0);}
        long accounted = (long) failures + errors + skipped;
        if (!found || total < accounted) {
            return new MavenTestSummary(false, 0, 0, 0, 0, 0);
        }
        return new MavenTestSummary(true, total, total - failures - errors - skipped,
                failures, errors, skipped);
    }
}
