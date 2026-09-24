package com.agent;

import com.agent.tool.ToolErrorCode;
import com.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleUiTest {
    @Test
    void formatsUserPromptAndAgentMessage() {
        CapturedUi captured = capturedUi();

        captured.ui().promptUser(CliMode.CHAT);
        captured.ui().printAgentMessage("First line\nSecond line");

        String output = captured.text();
        assertTrue(output.contains("[CHAT] You > "));
        assertTrue(output.contains("Agent >"));
        assertTrue(output.contains("First line\nSecond line"));
    }

    @Test
    void formatsToolSuccessWithSafePathSummary() {
        CapturedUi captured = capturedUi();

        captured.ui().toolStarted("read_file", Map.of("path", "README.md"));
        captured.ui().toolFinished("read_file", ToolResult.success("large file content"));

        String output = captured.text();
        assertTrue(output.contains("Tool > read_file(\"README.md\") ..."));
        assertTrue(output.contains("Tool > read_file [OK]"));
        assertFalse(output.contains("large file content"));
    }

    @Test
    void formatsToolFailureWithErrorCode() {
        CapturedUi captured = capturedUi();

        captured.ui().toolStarted("apply_patch", Map.of("path", "Calculator.java"));
        captured.ui().toolFinished(
                "apply_patch",
                ToolResult.failure(ToolErrorCode.TEXT_NOT_FOUND, "old text was not found")
        );

        String output = captured.text();
        assertTrue(output.contains("Tool > apply_patch(\"Calculator.java\") ..."));
        assertTrue(output.contains("Tool > apply_patch [FAIL] TEXT_NOT_FOUND"));
        assertFalse(output.contains("old text was not found"));
    }

    @Test
    void redactsPatchBodiesFromArgumentSummary() {
        CapturedUi captured = capturedUi();
        String oldText = "sensitive old body that must not be printed";
        String newText = "large replacement body that must not be printed";

        captured.ui().toolStarted("apply_patch", Map.of(
                "path", "Calculator.java",
                "oldText", oldText,
                "newText", newText
        ));

        String output = captured.text();
        assertTrue(output.contains("Calculator.java"));
        assertFalse(output.contains(oldText));
        assertFalse(output.contains(newText));
    }

    @Test
    void appendsStreamingTextWithoutAddingChunkNewlines() {
        CapturedUi captured = capturedUi();

        captured.ui().assistantMessageStarted();
        captured.ui().assistantTextDelta("Java");
        captured.ui().assistantTextDelta(" 是");
        captured.ui().assistantTextDelta("语言\n第二行");
        captured.ui().assistantMessageFinished();

        assertTrue(captured.text().contains("Agent > Java 是语言\n第二行"));
    }

    @Test
    void toolStatusStartsOnASeparateLineAfterStreamedTextFinishes() {
        CapturedUi captured = capturedUi();

        captured.ui().assistantMessageStarted();
        captured.ui().assistantTextDelta("我先读取。");
        captured.ui().assistantMessageFinished();
        captured.ui().toolStarted("read_file", Map.of("path", "README.md"));

        String normalized = captured.text().replace("\r\n", "\n");
        assertTrue(normalized.contains("Agent > 我先读取。\n\nTool > read_file"));
    }

    private static CapturedUi capturedUi() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        return new CapturedUi(
                new ConsoleUi(new PrintStream(output, true, StandardCharsets.UTF_8)),
                output
        );
    }

    private record CapturedUi(ConsoleUi ui, ByteArrayOutputStream output) {
        private String text() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
