package com.agent.llm;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/** Explicit environment-backed model settings shared by CLI and benchmark entry points. */
public final class ModelProviderConfig {
    public static final String GLM = "glm";
    public static final String OPENAI_COMPATIBLE = "openai-compatible";
    public static final String DEFAULT_GLM_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
    public static final String DEFAULT_GLM_MODEL = "glm-4-flash";

    private final String provider;
    private final String baseUrl;
    private final String model;
    private final String apiKey;

    private ModelProviderConfig(String provider, String baseUrl, String model, String apiKey) {
        this.provider = provider;
        this.baseUrl = trimTrailingSlashes(baseUrl);
        this.model = model;
        this.apiKey = apiKey;
    }

    public static ModelProviderConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    public static ModelProviderConfig fromEnvironment(Map<String, String> environment) {
        String provider = environment.getOrDefault("MODEL_PROVIDER", GLM).trim()
                .toLowerCase(Locale.ROOT);
        if (provider.isEmpty()) provider = GLM;
        return switch (provider) {
            case GLM -> new ModelProviderConfig(
                    GLM,
                    environment.getOrDefault("MODEL_BASE_URL", DEFAULT_GLM_BASE_URL),
                    nonBlankOrDefault(environment.get("MODEL_NAME"), DEFAULT_GLM_MODEL),
                    environment.get("GLM_API_KEY")
            );
            case OPENAI_COMPATIBLE -> new ModelProviderConfig(
                    OPENAI_COMPATIBLE,
                    require(environment.get("MODEL_BASE_URL"),
                            "Missing MODEL_BASE_URL for openai-compatible provider"),
                    require(environment.get("MODEL_NAME"),
                            "Missing MODEL_NAME for openai-compatible provider"),
                    blankToNull(environment.get("MODEL_API_KEY"))
            );
            default -> throw new IllegalArgumentException(
                    "Unsupported MODEL_PROVIDER: " + provider + " (expected glm or openai-compatible)");
        };
    }

    public String provider() { return provider; }
    public String baseUrl() { return baseUrl; }
    public String model() { return model; }

    String apiKey() { return apiKey; }

    public String displayProvider() {
        return GLM.equals(provider) ? "GLM" : "OpenAI-compatible";
    }

    String endpoint() {
        return baseUrl + "/chat/completions";
    }

    void validateOpenAiCompatibleBaseUrl() {
        try {
            URI uri = URI.create(baseUrl);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("MODEL_BASE_URL must be an absolute HTTP(S) URL");
            }
        } catch (IllegalArgumentException exception) {
            if ("MODEL_BASE_URL must be an absolute HTTP(S) URL".equals(exception.getMessage())) {
                throw exception;
            }
            throw new IllegalArgumentException("MODEL_BASE_URL must be a valid absolute HTTP(S) URL");
        }
    }

    @Override
    public String toString() {
        return "ModelProviderConfig[provider=" + provider + ", baseUrl=" + baseUrl
                + ", model=" + model + ", apiKey=" + (apiKey == null ? "unset" : "[REDACTED]") + "]";
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static String nonBlankOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String trimTrailingSlashes(String value) {
        if (value == null) return null;
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
