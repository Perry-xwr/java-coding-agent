package com.agent.llm;

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
    void openAiCompatibleAllowsOptionalKeyAndUsesNonStreamingClient() {
        ModelProviderConfig config = ModelProviderConfig.fromEnvironment(Map.of(
                "MODEL_PROVIDER", "openai-compatible",
                "MODEL_BASE_URL", "http://localhost:8000/v1/",
                "MODEL_NAME", "local-model"));

        assertInstanceOf(OpenAiCompatibleClient.class, LlmClientFactory.create(config));
        assertEquals("http://localhost:8000/v1", config.baseUrl());
        assertFalse(LlmClientFactory.create(config) instanceof StreamingLlmClient);
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
