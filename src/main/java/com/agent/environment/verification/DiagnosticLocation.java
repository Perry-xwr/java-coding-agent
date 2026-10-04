package com.agent.environment.verification;

/** Best-effort compiler diagnostic coordinates; absent coordinates are represented as zero. */
public record DiagnosticLocation(int line, int column) {
    public DiagnosticLocation {
        if (line < 0 || column < 0) throw new IllegalArgumentException("coordinates must not be negative");
    }
    public static DiagnosticLocation unknown() { return new DiagnosticLocation(0, 0); }
    public boolean known() { return line > 0; }
}
