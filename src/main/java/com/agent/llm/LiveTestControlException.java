package com.agent.llm;

import java.io.IOException;

/** A deliberate stop from the explicit live-test request/deadline gate. */
public final class LiveTestControlException extends IOException {
    public enum Code { REQUEST_BUDGET_EXCEEDED, TASK_TIMEOUT, DIAGNOSTICS_WRITE_FAILED }

    private final Code code;

    public LiveTestControlException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }
}
