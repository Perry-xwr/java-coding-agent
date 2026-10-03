package com.agent.benchmark.adaptiveplanning;

import com.agent.agent.AgentTrajectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/** Benchmark-only trajectory serializer that removes credential-bearing fields and values. */
public final class AdaptivePlanningTrajectoryWriter {
    private static final int MAX_TEXT_CHARS = 10_000;
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+");
    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile(
            "(?i)((?:glm[_-]?api[_-]?key|authorization|access[_-]?token|api[_-]?key|password|secret)\\s*[:=]\\s*)[^\\s\\\"']+");

    private final Path outputDirectory;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdaptivePlanningTrajectoryWriter(Path outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public Path write(AgentTrajectory trajectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path target = outputDirectory.resolve(trajectory.runId() + ".json");
        JsonNode clean = sanitize(mapper.valueToTree(trajectory), null);
        Files.writeString(target, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(clean),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return target;
    }

    private JsonNode sanitize(JsonNode node, String fieldName) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isCredentialField(field.getKey())) object.put(field.getKey(), "[REDACTED]");
                else object.set(field.getKey(), sanitize(field.getValue(), field.getKey()));
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int index = 0; index < array.size(); index++) {
                array.set(index, sanitize(array.get(index), fieldName));
            }
            return array;
        }
        if (node.isTextual()) {
            String text = redact(node.asText());
            if (isLargeTextField(fieldName) && text.length() > MAX_TEXT_CHARS) {
                text = text.substring(0, MAX_TEXT_CHARS) + "...[truncated]";
            }
            return mapper.getNodeFactory().textNode(text);
        }
        return node;
    }

    private static boolean isCredentialField(String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
        return normalized.contains("authorization") || normalized.contains("apikey")
                || normalized.contains("token") || normalized.contains("password")
                || normalized.contains("secret") || normalized.contains("credential");
    }

    private static boolean isLargeTextField(String fieldName) {
        return "output".equals(fieldName) || "content".equals(fieldName)
                || "rawArguments".equals(fieldName) || "finalAnswer".equals(fieldName)
                || "errorMessage".equals(fieldName);
    }

    private static String redact(String value) {
        String result = value;
        String key = System.getenv("GLM_API_KEY");
        if (key != null && !key.isBlank()) result = result.replace(key, "[REDACTED]");
        result = BEARER.matcher(result).replaceAll("$1[REDACTED]");
        return CREDENTIAL_ASSIGNMENT.matcher(result).replaceAll("$1[REDACTED]");
    }
}
