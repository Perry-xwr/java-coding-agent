package com.agent.tui.prototype;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Human-readable presentation for machine-oriented local status messages. */
final class TuiStatusFormatter {
    private static final Pattern FIELD = Pattern.compile("([A-Z_]+)=([^\\s]+)");

    private TuiStatusFormatter() { }

    static String present(String text) {
        if (text == null || !text.startsWith("LAST_TURN_STATUS=")) return text;
        int newline = text.indexOf('\n');
        String diagnostic = newline < 0 ? text : text.substring(0, newline);
        String ledger = newline < 0 ? "" : text.substring(newline + 1);
        var fields = fields(diagnostic);
        StringBuilder out = new StringBuilder("Memory diagnostics\n");
        out.append("  Run       ").append(value(fields, "AUTO_MEMORY_STATUS", value(fields, "LAST_TURN_STATUS", "UNKNOWN")));
        String at=value(fields,"AT","");
        if(!at.isBlank())out.append("  ·  ").append(localTime(at));
        out.append('\n');
        out.append("  Access    Memory ").append(onOff(value(fields, "MEMORY_ENABLED", "false")))
                .append("  ·  Auto ").append(onOff(value(fields, "AUTO_MEMORY_ENABLED", "false")))
                .append("  ·  Semantic ").append(onOff(value(fields, "SEMANTIC_ENABLED", "false"))).append('\n');
        out.append("  Rules     ").append(value(fields, "RULE_CANDIDATES", "0"))
                .append(" candidates  ·  Effective ").append(value(fields, "EFFECTIVE_SAVES", "0"))
                .append("  ·  Pending ").append(value(fields, "PENDING", "0"))
                .append("  ·  Rejected ").append(value(fields, "REJECTED", "0"));
        String rejectedReasons=value(fields,"REJECTION_REASONS","NONE");
        if(!rejectedReasons.equals("NONE"))out.append("  ·  ").append(rejectedReasons);
        out.append('\n');
        String semantic = value(fields, "SEMANTIC_STATUS", "UNKNOWN");
        String reason = value(fields, "SEMANTIC_SKIP_REASON", value(fields, "SKIP_REASON", "NONE"));
        out.append("  Semantic  ").append(semantic);
        if (!reason.equals("NONE") && !reason.equals("NO_RECORD")) out.append("  ·  ").append(reason);
        String error = value(fields, "ERROR", "NONE");
        if (!error.equals("NONE")) out.append("  ·  Error ").append(error);
        String failureCode=value(fields,"FAILURE_CODE","NONE");
        if(!failureCode.equals("NONE"))out.append("  ·  Failure ").append(failureCode);
        if (!ledger.isBlank()) out.append("\n\n").append(presentLedger(ledger));
        return out.toString();
    }

    private static String presentLedger(String ledger) {
        String attempts = between(ledger, "semantic reserved requests=", " ");
        String today = between(ledger, "todayUsed=", ";");
        String session = between(ledger, "currentSessionUsed=", ";");
        String cooldown = between(ledger, "cooldownRemainingMs=", ";");
        String complete = between(ledger, "complete PROVIDER_REPORTED records=", ",");
        String unknown = between(ledger, "Usage UNKNOWN=", ";");
        String input = between(ledger, "input=", ",");
        String output = between(ledger, "output=", ",");
        String total = between(ledger, "total=", " ");
        String cooldownLabel = "UNKNOWN";
        try {
            long millis = Long.parseLong(cooldown);
            cooldownLabel = millis == 0 ? "ready" : (millis + 999) / 1000 + "s remaining";
        } catch (NumberFormatException ignored) { }
        return "Semantic request budget\n"
                + "  Attempts  " + safe(attempts) + " total  ·  today " + safe(today) + "  ·  session " + safe(session) + "\n"
                + "  Cooldown  " + cooldownLabel + "\n"
                + "  Usage     " + safe(input) + " input  ·  " + safe(output) + " output  ·  " + safe(total) + " total tokens\n"
                + "  Records   provider reported " + safe(complete) + "  ·  missing usage " + safe(unknown) + " UNKNOWN\n"
                + "  Cost      UNKNOWN  ·  automatic retries disabled";
    }

    private static java.util.Map<String,String> fields(String line) {
        var values = new java.util.LinkedHashMap<String,String>();
        Matcher matcher = FIELD.matcher(line);
        while (matcher.find()) values.put(matcher.group(1), matcher.group(2));
        return values;
    }
    private static String value(java.util.Map<String,String> fields, String key, String fallback) {
        return fields.getOrDefault(key, fallback);
    }
    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        if (from < 0) return "UNKNOWN";
        from += start.length();
        int to = end.isEmpty() ? -1 : source.indexOf(end, from);
        return safe((to < 0 ? source.substring(from) : source.substring(from, to)).strip());
    }
    private static String safe(String value) { return value == null || value.isBlank() ? "UNKNOWN" : value; }
    private static String onOff(String value) { return Boolean.parseBoolean(value) ? "ON" : "OFF"; }
    private static String localTime(String value){
        try{return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(value));}
        catch(java.time.DateTimeException invalid){return "time unavailable";}
    }
}
