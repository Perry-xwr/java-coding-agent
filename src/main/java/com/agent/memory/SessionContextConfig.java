package com.agent.memory;

public record SessionContextConfig(boolean enabled, int budgetEstimatedTokens, int recentTurns) {
    public SessionContextConfig {
        if (budgetEstimatedTokens < 128 || budgetEstimatedTokens > 1_000_000 || recentTurns < 1 || recentTurns > 20) {
            throw new IllegalArgumentException("Memory budget must be 128..1000000 ESTIMATED tokens; recent turns must be 1..20");
        }
    }
    public static SessionContextConfig fromEnvironment() {
        String enabled = setting("agent.sessionMemory.enabled", "AGENT_SESSION_MEMORY_ENABLED", "true");
        if (!enabled.equalsIgnoreCase("true") && !enabled.equalsIgnoreCase("false")) throw new IllegalArgumentException("Memory enabled must be true or false");
        return new SessionContextConfig(Boolean.parseBoolean(enabled),
                Integer.parseInt(setting("agent.sessionMemory.budgetEstimatedTokens", "AGENT_SESSION_MEMORY_BUDGET_ESTIMATED_TOKENS", "8192")),
                Integer.parseInt(setting("agent.sessionMemory.recentTurns", "AGENT_SESSION_MEMORY_RECENT_TURNS", "3")));
    }
    private static String setting(String property, String environment, String fallback) {
        String value = System.getProperty(property);
        if (value == null) value = System.getenv(environment);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
