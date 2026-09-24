package com.agent.trajectory;

import com.agent.agent.AgentTrajectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class TrajectoryJsonWriter {
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 10_000;

    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+"
    );
    private static final Pattern GLM_KEY_ASSIGNMENT = Pattern.compile(
            "(?i)(GLM_API_KEY\\s*[:=]\\s*)[^\\s\\\"']+"
    );

    private final Path outputDirectory;
    private final int maxOutputChars;
    private final ObjectMapper objectMapper;

    public TrajectoryJsonWriter(Path outputDirectory) {
        this(outputDirectory, DEFAULT_MAX_OUTPUT_CHARS);
    }

    public TrajectoryJsonWriter(Path outputDirectory, int maxOutputChars) {
        this.outputDirectory = Objects.requireNonNull(
                outputDirectory,
                "outputDirectory must not be null"
        );
        if (maxOutputChars < 1) {
            throw new IllegalArgumentException("maxOutputChars must be positive");
        }
        this.maxOutputChars = maxOutputChars;
        this.objectMapper = new ObjectMapper();
    }

    public Path write(AgentTrajectory trajectory) throws IOException {
        Objects.requireNonNull(trajectory, "trajectory must not be null");
        Files.createDirectories(outputDirectory);
        Path target = outputDirectory.resolve(trajectory.runId() + ".json");
        JsonNode sanitized = sanitize(objectMapper.valueToTree(trajectory), null);
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(writer, sanitized);
        }
        return target;
    }

    private JsonNode sanitize(JsonNode node, String fieldName) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                object.set(field.getKey(), sanitize(field.getValue(), field.getKey()));
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
            String value = redact(node.asText());
            if ("output".equals(fieldName) && value.length() > maxOutputChars) {
                value = value.substring(0, maxOutputChars) + "...[truncated]";
            }
            return objectMapper.getNodeFactory().textNode(value);
        }
        return node;
    }

    private static String redact(String value) {
        String redacted = value;
        String apiKey = System.getenv("GLM_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            redacted = redacted.replace(apiKey, "[REDACTED]");
        }
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("$1[REDACTED]");
        return GLM_KEY_ASSIGNMENT.matcher(redacted).replaceAll("$1[REDACTED]");
    }
}
