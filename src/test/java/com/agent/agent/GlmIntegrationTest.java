package com.agent.agent;

import com.agent.llm.GlmClient;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "glm.integration", matches = "true")
@EnabledIfEnvironmentVariable(named = "GLM_API_KEY", matches = ".+")
class GlmIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void realGlmCallsReadFileToolAndReturnsFinalAnswer() throws IOException {
        Files.writeString(tempDir.resolve("README.md"), "Agent CLI README live tool marker");
        Agent agent = new Agent(new GlmClient(), ToolRegistry.withFileTools(tempDir));

        String answer = agent.run("读取README.md");

        assertTrue(!answer.isBlank());
        assertTrue(agent.history().stream().anyMatch(
                message -> message.role().equals("tool")
                        && message.content().equals("Agent CLI README live tool marker")
                        && message.toolCallId() != null
        ));
        assertTrue(agent.history().stream().anyMatch(
                message -> message.role().equals("assistant")
                        && message.toolCalls().stream().anyMatch(
                                call -> call.get("function").toString().contains("read_file")
                        )
        ));
    }
}
