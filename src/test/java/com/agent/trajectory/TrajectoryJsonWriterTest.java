package com.agent.trajectory;

import com.agent.agent.AgentActionType;
import com.agent.agent.AgentStep;
import com.agent.agent.AgentTrajectory;
import com.agent.agent.TerminationReason;
import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrajectoryJsonWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesPrettyJsonWithStructuredErrorAndDuration() throws IOException {
        AgentStep step = new AgentStep(
                1,
                AgentActionType.TOOL_CALL,
                "read_file",
                "call-1",
                "{\"path\":\"missing.txt\"}",
                Map.of("path", "missing.txt"),
                ToolResult.failure(ToolErrorCode.FILE_NOT_FOUND, "missing.txt"),
                null,
                null,
                100,
                3
        );
        AgentTrajectory trajectory = new AgentTrajectory(
                "run-123",
                "read missing",
                List.of(step),
                null,
                TerminationReason.MAX_STEPS,
                false,
                null,
                100,
                5
        );

        Path written = new TrajectoryJsonWriter(tempDir).write(trajectory);
        JsonNode json = new ObjectMapper().readTree(written.toFile());

        assertEquals("run-123", json.path("runId").asText());
        assertEquals("MAX_STEPS", json.path("terminationReason").asText());
        assertEquals(5, json.path("durationMs").asLong());
        assertEquals("FILE_NOT_FOUND", json.path("steps").get(0)
                .path("toolResult").path("errorCode").asText());
    }

    @Test
    void truncatesPersistedOutputAndRedactsBearerToken() throws IOException {
        String sensitiveOutput = "Bearer secret-token 12345678901234567890";
        AgentStep step = new AgentStep(
                1,
                AgentActionType.TOOL_CALL,
                "read_file",
                "call-1",
                "{}",
                Map.of(),
                ToolResult.success(sensitiveOutput),
                null,
                null,
                100,
                1
        );
        AgentTrajectory trajectory = new AgentTrajectory(
                "run-redacted",
                "task",
                List.of(step),
                "done",
                TerminationReason.FINAL_ANSWER,
                true,
                null,
                100,
                2
        );

        Path written = new TrajectoryJsonWriter(tempDir, 20).write(trajectory);
        String json = Files.readString(written);

        assertFalse(json.contains("secret-token"));
        assertTrue(json.contains("[REDACTED]"));
        assertTrue(json.contains("...[truncated]"));
    }
}
