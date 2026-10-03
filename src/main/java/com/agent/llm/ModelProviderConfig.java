package com.agent.llm;

import java.net.URI;
import java.net.InetSocketAddress;
import java.net.Proxy;
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
    private final Proxy proxy;

    private ModelProviderConfig(String provider, String baseUrl, String model, String apiKey, Proxy proxy) {
        this.provider = provider;
        this.baseUrl = trimTrailingSlashes(baseUrl);
        this.model = model;
        this.apiKey = apiKey;
        this.proxy = proxy;
    }

    public static ModelProviderConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    public static ModelProviderConfig fromEnvironment(Map<String, String> environment) {
        String provider = environment.getOrDefault("MODEL_PROVIDER", GLM).trim()
                .toLowerCase(Locale.ROOT);
        if (provider.isEmpty()) provider = GLM;
        Proxy proxy = parseProxy(environment.get("MODEL_PROXY"));
        return switch (provider) {
            case GLM -> new ModelProviderConfig(
                    GLM,
                    environment.getOrDefault("MODEL_BASE_URL", DEFAULT_GLM_BASE_URL),
                    nonBlankOrDefault(environment.get("MODEL_NAME"), DEFAULT_GLM_MODEL),
                    environment.get("GLM_API_KEY"), proxy
            );
            case OPENAI_COMPATIBLE -> new ModelProviderConfig(
                    OPENAI_COMPATIBLE,
                    require(environment.get("MODEL_BASE_URL"),
                            "Missing MODEL_BASE_URL for openai-compatible provider"),
                    require(environment.get("MODEL_NAME"),
                            "Missing MODEL_NAME for openai-compatible provider"),
                    blankToNull(environment.get("MODEL_API_KEY")), proxy
            );
            default -> throw new IllegalArgumentException(
                    "Unsupported MODEL_PROVIDER: " + provider + " (expected glm or openai-compatible)");
        };
    }

    public String provider() { return provider; }
    public String baseUrl() { return baseUrl; }
    public String model() { return model; }

    String apiKey() { return apiKey; }

    Proxy proxy() { return proxy; }

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
                + ", model=" + model + ", proxy=" + (proxy == null ? "direct" : proxy.address())
                + ", apiKey=" + (apiKey == null ? "unset" : "[REDACTED]") + "]";
    }

    private static Proxy parseProxy(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getRawUserInfo() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw invalidProxy();
            }
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(uri.getHost(), uri.getPort()));
        } catch (IllegalArgumentException exception) {
            if ("MODEL_PROXY must be an absolute http URL with a valid host and port".equals(exception.getMessage())) {
                throw exception;
            }
            throw invalidProxy();
        }
    }

    private static IllegalArgumentException invalidProxy() {
        return new IllegalArgumentException(
                "MODEL_PROXY must be an absolute http URL with a valid host and port");
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
