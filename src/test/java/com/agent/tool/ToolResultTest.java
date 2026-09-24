package com.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultTest {
    @TempDir
    Path tempDir;

    @Test
    void representsSuccessfulResult() {
        ToolResult result = ToolResult.success("output");

        assertTrue(result.success());
        assertEquals("output", result.output());
        assertNull(result.errorCode());
        assertNull(result.errorMessage());
    }

    @Test
    void classifiesMissingFile() {
        ToolResult result = ToolRegistry.withFileTools(tempDir).execute(
                "read_file",
                "{\"path\":\"missing.txt\"}"
        );

        assertTrue(!result.success());
        assertEquals(ToolErrorCode.FILE_NOT_FOUND, result.errorCode());
    }

    @Test
    void classifiesWorkspaceViolation() {
        ToolResult result = ToolRegistry.withFileTools(tempDir).execute(
                "read_file",
                "{\"path\":\"../outside.txt\"}"
        );

        assertTrue(!result.success());
        assertEquals(ToolErrorCode.WORKSPACE_VIOLATION, result.errorCode());
    }

    @Test
    void classifiesUnknownTool() {
        ToolResult result = new ToolRegistry().execute("unknown", "{}");

        assertTrue(!result.success());
        assertEquals(ToolErrorCode.TOOL_NOT_FOUND, result.errorCode());
    }

    @Test
    void classifiesToolExecutionException() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public String description() {
                return "Always fails.";
            }

            @Override
            public ToolResult execute(String arguments) {
                throw new IllegalStateException("boom");
            }
        });

        ToolResult result = registry.execute("broken", "{}");

        assertTrue(!result.success());
        assertEquals(ToolErrorCode.TOOL_EXECUTION_ERROR, result.errorCode());
        assertEquals("boom", result.errorMessage());
    }
}
