package com.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;

/** Single construction point for configured model backends. */
public final class LlmClientFactory {
    private LlmClientFactory() {
    }

    public static LLMClient create() {
        return create(ModelProviderConfig.fromEnvironment());
    }

    public static LLMClient create(ModelProviderConfig config) {
        return create(config, false);
    }

    public static LLMClient createBenchmark() {
        return create(ModelProviderConfig.fromEnvironment(), true);
    }

    public static LLMClient createBenchmark(ModelProviderConfig config) {
        return create(config, true);
    }

    private static LLMClient create(ModelProviderConfig config, boolean benchmark) {
        if (ModelProviderConfig.GLM.equals(config.provider())) {
            String key = config.apiKey();
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("Environment variable GLM_API_KEY is not set");
            }
            return GlmClient.configured(key, config.endpoint(), config.model(), benchmark, config.proxy());
        }
        if (ModelProviderConfig.OPENAI_COMPATIBLE.equals(config.provider())) {
            config.validateOpenAiCompatibleBaseUrl();
            return new OpenAiCompatibleClient(config.endpoint(), config.model(), config.apiKey(), config.proxy());
        }
        throw new IllegalArgumentException("Unsupported model provider: " + config.provider());
    }
}
