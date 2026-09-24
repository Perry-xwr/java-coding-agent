package com.agent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AgentPlanCodec {
    private static final Pattern UPDATE = Pattern.compile(
            "<plan_update>\\s*(\\{.*?})\\s*</plan_update>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AgentPlanCodec() {
    }

    static ParsedContent parse(String content) {
        String text = content == null ? "" : content;
        Matcher matcher = UPDATE.matcher(text);
        if (!matcher.find()) {
            return new ParsedContent(null, text);
        }
        try {
            AgentPlan plan = OBJECT_MAPPER.readValue(matcher.group(1), AgentPlan.class);
            String visibleContent = (text.substring(0, matcher.start())
                    + text.substring(matcher.end())).trim();
            return new ParsedContent(plan, visibleContent);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return new ParsedContent(null, text);
        }
    }

    record ParsedContent(AgentPlan plan, String visibleContent) {
    }
}
