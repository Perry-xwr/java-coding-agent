package com.agent.agent;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Side-effect-free planning requests and strict conversion into the existing AgentPlan model. */
final class AgentPlanner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYSTEM_PROMPT = """
            [PLANNING_PHASE]
            Create only a concise executable plan for the user task. Return one JSON object with
            a non-empty "goal" and a "steps" array of 1 to 8 objects, each containing "id" and
            "description". Do not call tools or produce a final answer. Do not claim files were
            inspected or tests passed, invent workspace facts, or expose hidden reasoning. Refer
            to unknown files conditionally. Plan may conceptually use file discovery/read, safe edits,
            reread, and applicable test verification, but never call tools in this phase. Plan actions
            only; this plan is guidance, not evidence.
            """;

    AgentPlan create(LLMClient client, String task) throws IOException {
        LLMResponse response = client.chat(List.of(
                Message.system(SYSTEM_PROMPT), Message.user(task)), List.of());
        if (response == null || !response.toolCalls().isEmpty()) {
            throw new IllegalArgumentException("planning response must not contain tool calls");
        }
        return parse(response.content());
    }

    AgentPlan replan(
            LLMClient client,
            String task,
            AgentPlan currentPlan,
            ToolResult failure,
            String toolName
    ) throws IOException {
        String observation = "Tool " + toolName + " failed with " + failure.errorCode()
                + ": " + bounded(failure.errorMessage(), 500)
                + (failure.output() == null ? "" : "\nObserved output: " + bounded(failure.output(), 1200));
        String prompt = "Replan the remaining actions for the original task. The prior structured plan is "
                + "guidance only; the following tool result is the new observed evidence. Preserve already "
                + "completed action steps when they still apply.\nOriginal task: " + task
                + "\nPrior plan: " + MAPPER.valueToTree(currentPlan).toString()
                + "\nObserved failure: " + observation
                + "\nReturn only the required JSON plan object.";
        LLMResponse response = client.chat(List.of(
                Message.system(SYSTEM_PROMPT), Message.user(prompt)), List.of());
        if (response == null || !response.toolCalls().isEmpty()) {
            throw new IllegalArgumentException("replanning response must not contain tool calls");
        }
        return preserveCompleted(parse(response.content()), currentPlan);
    }

    private static AgentPlan parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("planning response is empty");
        }
        String json = content.trim();
        if (json.startsWith("```")) {
            int firstLine = json.indexOf('\n');
            int closing = json.lastIndexOf("```");
            if (firstLine < 0 || closing <= firstLine) {
                throw new IllegalArgumentException("planning JSON fence is malformed");
            }
            json = json.substring(firstLine + 1, closing).trim();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            String goal = requiredText(root, "goal");
            JsonNode steps = root.get("steps");
            if (steps == null || !steps.isArray() || steps.isEmpty()
                    || steps.size() > AgentPlan.MAX_REQUIREMENTS) {
                throw new IllegalArgumentException("steps must contain between 1 and 8 entries");
            }
            List<TaskRequirement> requirements = new ArrayList<>();
            int index = 0;
            for (JsonNode step : steps) {
                String id = step.hasNonNull("id") ? step.get("id").asText() : "S" + (index + 1);
                requirements.add(new TaskRequirement(id, requiredText(step, "description"),
                        RequirementStatus.PENDING, ""));
                index++;
            }
            return new AgentPlan(goal, requirements, requirements.get(0).id(), "");
        } catch (IOException exception) {
            throw new IllegalArgumentException("planning response is not valid JSON", exception);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("planning field '" + field + "' must be non-empty text");
        }
        return value.asText().trim();
    }

    private static AgentPlan preserveCompleted(AgentPlan replacement, AgentPlan previous) {
        List<TaskRequirement> requirements = replacement.requirements().stream().map(next ->
                previous.requirements().stream()
                        .filter(old -> old.status() == RequirementStatus.COMPLETED
                                && old.description().equals(next.description()))
                        .findFirst()
                        .map(old -> new TaskRequirement(next.id(), next.description(),
                                RequirementStatus.COMPLETED, old.evidence()))
                        .orElse(next)
        ).toList();
        String focus = requirements.stream()
                .filter(requirement -> requirement.status() != RequirementStatus.COMPLETED)
                .findFirst().map(TaskRequirement::id).orElse(replacement.currentFocus());
        return new AgentPlan(replacement.goal(), requirements, focus, "");
    }

    private static String bounded(String text, int max) {
        if (text == null || text.isBlank()) {
            return "(no message)";
        }
        String normalized = text.replaceAll("(?i)Bearer\\s+\\S+", "Bearer [REDACTED]");
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…";
    }
}
