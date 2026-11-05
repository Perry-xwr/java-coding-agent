package com.agent.llm;

import com.agent.diagnostics.LiveTestDiagnostics;
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
        if (LiveTestControl.enabled()) {
            throw new IllegalStateException("Benchmark clients are disabled in explicit live-test mode");
        }
        return create(ModelProviderConfig.fromEnvironment(), true);
    }

    public static LLMClient createBenchmark(ModelProviderConfig config) {
        if (LiveTestControl.enabled()) {
            throw new IllegalStateException("Benchmark clients are disabled in explicit live-test mode");
        }
        return create(config, true);
    }

    private static LLMClient create(ModelProviderConfig config, boolean benchmark) {
        LiveTestControl liveTestControl = !benchmark
                ? LiveTestControl.processInstanceIfEnabled() : null;
        if (liveTestControl != null) {
            LiveTestDiagnostics.configureProvider(config.displayProvider(), config.model(), config.endpoint());
        }
        if (ModelProviderConfig.GLM.equals(config.provider())) {
            String key = config.apiKey();
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("Environment variable GLM_API_KEY is not set");
            }
            if (liveTestControl != null) {
                return GlmClient.configuredForLiveTest(key, config.endpoint(), config.model(),
                        config.proxy(), liveTestControl);
            }
            return GlmClient.configured(key, config.endpoint(), config.model(), benchmark, config.proxy());
        }
        if (ModelProviderConfig.OPENAI_COMPATIBLE.equals(config.provider())
                || ModelProviderConfig.DEEPSEEK.equals(config.provider())) {
            if (ModelProviderConfig.DEEPSEEK.equals(config.provider())
                    && (config.apiKey() == null || config.apiKey().isBlank())) {
                throw new IllegalStateException("Environment variable DEEPSEEK_API_KEY is not set");
            }
            config.validateOpenAiCompatibleBaseUrl();
            OpenAiCompatibleClient compatibleClient;
            if (liveTestControl != null) {
                compatibleClient = OpenAiCompatibleClient.configuredForLiveTest(config.endpoint(), config.model(),
                        config.apiKey(), config.proxy(), liveTestControl, config.deepSeekNonThinking());
            } else {
                compatibleClient = new OpenAiCompatibleClient(config.endpoint(), config.model(), config.apiKey(),
                        config.proxy(), config.deepSeekNonThinking());
            }
            return ModelProviderConfig.DEEPSEEK.equals(config.provider())
                    ? new DeepSeekStreamingClient(compatibleClient)
                    : compatibleClient;
        }
        throw new IllegalArgumentException("Unsupported model provider: " + config.provider());
    }
}
