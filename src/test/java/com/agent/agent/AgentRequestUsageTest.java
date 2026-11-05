package com.agent.agent;

import com.agent.llm.OpenAiCompatibleClient;
import com.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRequestUsageTest {
    @TempDir Path workspace;

    @Test
    void associatesMultipleProviderRequestsWithOneAgentTurnAndAggregatesServerUsage() throws Exception {
        Files.writeString(workspace.resolve("note.txt"), "fixture content");
        AtomicInteger request = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            int index = request.getAndIncrement();
            String body = index == 0
                    ? "{\"model\":\"fixture-model\",\"choices\":[{\"message\":{\"content\":null,"
                    + "\"tool_calls\":[{\"id\":\"call-read\",\"type\":\"function\",\"function\":{"
                    + "\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"note.txt\\\"}\"}}]}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12,"
                    + "\"prompt_cache_hit_tokens\":4,\"prompt_cache_miss_tokens\":6}}"
                    : "{\"model\":\"fixture-model\",\"choices\":[{\"message\":{\"content\":\"Read complete.\"}}],"
                    + "\"usage\":{\"prompt_tokens\":15,\"completion_tokens\":3,\"total_tokens\":18,"
                    + "\"prompt_cache_hit_tokens\":7,\"prompt_cache_miss_tokens\":8}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions",
                    "fixture-model", null);
            Agent agent = new Agent(client, ToolRegistry.withFileTools(workspace),
                    "You are a read-only repository assistant.", 4, TaskMode.READ_ONLY);

            AgentRunResult result = agent.runWithTrajectory("Read note.txt");

            assertTrue(result.trajectory().completed());
            assertEquals(2, request.get());
            assertEquals(2, result.usageSummary().requests().size());
            assertEquals("READ", result.usageSummary().requests().get(0).profile());
            assertEquals(result.usageSummary().requests().get(0).runId(),
                    result.usageSummary().requests().get(1).runId());
            assertEquals(1, result.usageSummary().requests().get(0).agentTurn());
            assertEquals(1, result.usageSummary().requests().get(1).agentTurn());
            assertEquals(result.usageSummary().requests().get(0).requestSequence() + 1,
                    result.usageSummary().requests().get(1).requestSequence());
            assertEquals(25, result.usageSummary().inputTokens());
            assertEquals(5, result.usageSummary().outputTokens());
            assertEquals(30, result.usageSummary().totalTokens());
            assertEquals(11, result.usageSummary().cacheHitTokens());
            assertEquals(14, result.usageSummary().cacheMissTokens());
            assertTrue(result.usageSummary().requests().get(1).context().messageCount()
                    > result.usageSummary().requests().get(0).context().messageCount());
            assertTrue(result.usageSummary().requests().get(1).context().payloadBytes()
                    > result.usageSummary().requests().get(0).context().payloadBytes());
            assertTrue(result.usageSummary().usageComplete());
        } finally {
            server.stop(0);
        }
    }
}
