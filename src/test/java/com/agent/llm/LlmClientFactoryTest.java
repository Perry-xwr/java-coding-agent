package com.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.net.InetSocketAddress;
import java.net.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmClientFactoryTest {
    @Test
    void defaultsToGlmAndExplicitGlmCreatesSameBackend() {
        ModelProviderConfig defaults = ModelProviderConfig.fromEnvironment(Map.of("GLM_API_KEY", "dummy"));
        ModelProviderConfig explicit = ModelProviderConfig.fromEnvironment(Map.of(
                "MODEL_PROVIDER", "glm", "GLM_API_KEY", "dummy"));

        assertEquals("glm", defaults.provider());
        assertEquals(ModelProviderConfig.DEFAULT_GLM_MODEL, defaults.model());
        assertInstanceOf(GlmClient.class, LlmClientFactory.create(defaults));
        assertInstanceOf(GlmClient.class, LlmClientFactory.create(explicit));
        assertNull(((GlmClient) LlmClientFactory.create(defaults)).httpClientForTesting().proxy());
    }

    @Test
    void optionalModelProxyIsAppliedToConfiguredProviders() {
        ModelProviderConfig glmConfig = ModelProviderConfig.fromEnvironment(Map.of(
                "GLM_API_KEY", "dummy", "MODEL_PROXY", "http://127.0.0.1:7897"));
        Proxy glmProxy = ((GlmClient) LlmClientFactory.create(glmConfig)).httpClientForTesting().proxy();
        assertEquals(new InetSocketAddress("127.0.0.1", 7897), glmProxy.address());

        ModelProviderConfig compatibleConfig = ModelProviderConfig.fromEnvironment(Map.of(
                "MODEL_PROVIDER", "openai-compatible",
                "MODEL_BASE_URL", "http://localhost:8000/v1",
                "MODEL_NAME", "local-model",
                "MODEL_PROXY", "http://localhost:8899"));
        Proxy compatibleProxy = ((OpenAiCompatibleClient) LlmClientFactory.create(compatibleConfig))
                .httpClientForTesting().proxy();
        assertEquals(new InetSocketAddress("localhost", 8899), compatibleProxy.address());
    }

    @Test
    void invalidModelProxyHasClearNonSensitiveError() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ModelProviderConfig.fromEnvironment(Map.of(
                        "MODEL_PROXY", "abc", "GLM_API_KEY", "must-not-appear")));
        assertEquals("MODEL_PROXY must be an absolute http URL with a valid host and port", error.getMessage());
        assertFalse(error.toString().contains("must-not-appear"));
    }

    @Test
    void openAiCompatibleAllowsOptionalKeyAndUsesNonStreamingClient() throws Exception {
        ModelProviderConfig config = ModelProviderConfig.fromEnvironment(Map.of(
                "MODEL_PROVIDER", "openai-compatible",
                "MODEL_BASE_URL", "http://localhost:8000/v1/",
                "MODEL_NAME", "local-model"));

        assertInstanceOf(OpenAiCompatibleClient.class, LlmClientFactory.create(config));
        assertEquals("http://localhost:8000/v1", config.baseUrl());
        OpenAiCompatibleClient client = (OpenAiCompatibleClient) LlmClientFactory.create(config);
        assertFalse(LlmClientFactory.create(config) instanceof StreamingLlmClient);
        var request = client.buildRequest(java.util.List.of(Message.user("hi")), java.util.List.of());
        Buffer requestBody = new Buffer();
        request.body().writeTo(requestBody);
        JsonNode payload = new ObjectMapper().readTree(requestBody.readUtf8());
        assertTrue(payload.path("thinking").isMissingNode());
    }

    @Test
    void openAiCompatibleRequiresBaseUrlAndModel() {
        IllegalArgumentException missingBase = assertThrows(IllegalArgumentException.class,
                () -> ModelProviderConfig.fromEnvironment(Map.of(
                        "MODEL_PROVIDER", "openai-compatible", "MODEL_NAME", "local-model")));
        assertEquals("Missing MODEL_BASE_URL for openai-compatible provider", missingBase.getMessage());

        IllegalArgumentException missingModel = assertThrows(IllegalArgumentException.class,
                () -> ModelProviderConfig.fromEnvironment(Map.of(
                        "MODEL_PROVIDER", "openai-compatible", "MODEL_BASE_URL", "http://localhost:8000/v1")));
        assertEquals("Missing MODEL_NAME for openai-compatible provider", missingModel.getMessage());
    }

    @Test
    void deepSeekUsesStreamingCompatibleAdapterWithExplicitNonThinkingMode() throws Exception {
        ModelProviderConfig config = ModelProviderConfig.fromEnvironment(Map.of(
                "MODEL_PROVIDER", "deepseek",
                "DEEPSEEK_API_KEY", "test-key",
                "MODEL_PROXY", "http://127.0.0.1:7897"));

        assertEquals("deepseek", config.provider());
        assertEquals("DeepSeek", config.displayProvider());
        assertEquals("https://api.deepseek.com", config.baseUrl());
        assertEquals("deepseek-flash", config.model());
        assertEquals("https://api.deepseek.com/chat/completions", config.endpoint());

        DeepSeekStreamingClient streamingClient = assertInstanceOf(
                DeepSeekStreamingClient.class, LlmClientFactory.create(config));
        assertInstanceOf(StreamingLlmClient.class, streamingClient);
        OpenAiCompatibleClient client = streamingClient.delegateForTesting();
        assertFalse(client.httpClientForTesting().retryOnConnectionFailure());
        assertFalse(client.httpClientForTesting().followRedirects());
        assertFalse(client.httpClientForTesting().followSslRedirects());
        ToolDefinition tool = new ToolDefinition("read_file", "Read a workspace file", Map.of(
                "type", "object", "properties", Map.of("path", Map.of("type", "string")),
                "required", java.util.List.of("path")));
        var request = client.buildRequest(java.util.List.of(Message.user("test")), java.util.List.of(tool));
        Buffer requestBody = new Buffer();
        request.body().writeTo(requestBody);
        JsonNode payload = new ObjectMapper().readTree(requestBody.readUtf8());
        assertEquals("disabled", payload.path("thinking").path("type").asText());
        assertEquals("deepseek-flash", payload.path("model").asText());
        assertEquals("read_file", payload.path("tools").get(0).path("function").path("name").asText());
        assertEquals("Bearer test-key", request.header("Authorization"));
    }

    @Test
    void deepSeekRequiresItsDedicatedApiKeyVariable() {
        ModelProviderConfig config = ModelProviderConfig.fromEnvironment(Map.of(
                "MODEL_PROVIDER", "deepseek"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> LlmClientFactory.create(config));
        assertEquals("Environment variable DEEPSEEK_API_KEY is not set", error.getMessage());
    }

    @Test
    void glmMissingKeyHasClearErrorAndConfigurationRedactsKey() {
        ModelProviderConfig missing = ModelProviderConfig.fromEnvironment(Map.of());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> LlmClientFactory.create(missing));
        assertEquals("Environment variable GLM_API_KEY is not set", error.getMessage());

        ModelProviderConfig configured = ModelProviderConfig.fromEnvironment(Map.of(
                "MODEL_PROVIDER", "openai-compatible",
                "MODEL_BASE_URL", "http://localhost:8000/v1",
                "MODEL_NAME", "local-model",
                "MODEL_API_KEY", "never-print-this"));
        assertFalse(configured.toString().contains("never-print-this"));
        assertTrue(configured.toString().contains("[REDACTED]"));
    }
}
