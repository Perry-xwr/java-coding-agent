package com.agent.tool;

import java.io.IOException;

public class WorkspaceViolationException extends IOException {
    public WorkspaceViolationException(String message) {
        super(message);
    }
}
