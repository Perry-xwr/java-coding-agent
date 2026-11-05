package com.agent.agent;

import com.agent.llm.ToolCall;
import com.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskDiffCollectorTest {
    @TempDir Path workspace;

    @Test void capturesNewFileAndAssociatesToolCallAndVerification() throws Exception {
        TaskDiffCollector collector = new TaskDiffCollector(workspace);
        Files.createDirectories(workspace.resolve("src"));
        ToolCall call = call("create_file", "src/Hello.java", "1");
        var before = collector.before(call);
        String content = "class Hello {\n  // 中文\n}\n";
        Files.writeString(workspace.resolve("src/Hello.java"), content);
        collector.after(before, call, ToolResult.success("Created", Map.of("path", "src/Hello.java",
                "changed", true, "versionAfter", hash(content))));

        TaskChangeReview review = collector.finish("run-1", Map.of("src/Hello.java", "PASS"));
        assertEquals(1, review.changedFiles().size());
        var file = review.changedFiles().get(0);
        assertEquals(TaskChangeReview.ChangeType.ADDED, file.type());
        assertEquals(TaskChangeReview.Attribution.TOOL_EXECUTION, file.attribution());
        assertEquals("1", file.toolCallIds().get(0));
        assertEquals("PASS", file.verificationStatus());
        assertTrue(file.diff().contains("+class Hello"));
    }

    @Test void aggregatesMultipleEditsAndUsesPreTaskContentAsBaseline() throws Exception {
        Path file = workspace.resolve("A.java");
        Files.writeString(file, "class A {\n int a;\n}\n"); // Existing user change is baseline.
        TaskDiffCollector collector = new TaskDiffCollector(workspace);
        var first = call("apply_patch", "A.java", "one");
        var snapshot1 = collector.before(first);
        String changed1 = "class A {\n int b;\n}\n";
        Files.writeString(file, changed1);
        collector.after(snapshot1, first, changed("A.java", "class A {\n int a;\n}\n", changed1));
        var second = call("apply_patch", "A.java", "two");
        var snapshot2 = collector.before(second);
        String changed2 = "class A {\n int b;\n int c;\n}\n";
        Files.writeString(file, changed2);
        collector.after(snapshot2, second, changed("A.java", changed1, changed2));

        var review = collector.finish("run-2", Map.of());
        assertEquals(1, review.changedFiles().size());
        assertEquals(hash("class A {\n int a;\n}\n"), review.changedFiles().get(0).beforeSha256());
        assertEquals(2, review.changedFiles().get(0).toolCallIds().size());
        assertTrue(review.changedFiles().get(0).diff().contains("int c"));
    }

    @Test void failedAndNoOpToolsDoNotInventChanges() throws Exception {
        Files.writeString(workspace.resolve("A.txt"), "same");
        TaskDiffCollector collector = new TaskDiffCollector(workspace);
        var failed = call("apply_patch", "A.txt", "failed");
        collector.after(collector.before(failed), failed,
                ToolResult.failure(com.agent.tool.ToolErrorCode.TEXT_NOT_FOUND, "not found"));
        var noop = call("apply_patch", "A.txt", "noop");
        collector.after(collector.before(noop), noop,
                ToolResult.success("No change", Map.of("changed", false)));
        assertTrue(collector.finish("run-3", Map.of()).changedFiles().isEmpty());
    }

    @Test void externalChangeAfterMutationMakesAttributionUncertain() throws Exception {
        Path file = workspace.resolve("A.txt");
        Files.writeString(file, "before");
        TaskDiffCollector collector = new TaskDiffCollector(workspace);
        var call = call("apply_patch", "A.txt", "c1");
        var before = collector.before(call);
        Files.writeString(file, "agent result");
        collector.after(before, call, changed("A.txt", "before", "agent result"));
        Files.writeString(file, "external overwrite");
        var review = collector.finish("run-4", Map.of());
        assertEquals(TaskChangeReview.Attribution.UNCERTAIN, review.changedFiles().get(0).attribution());
    }

    @Test void sensitivePathsHideDiffAndWorkspaceEscapesAreIgnored() throws Exception {
        TaskDiffCollector collector = new TaskDiffCollector(workspace);
        var secret = call("create_file", ".env", "s");
        var snapshot = collector.before(secret);
        Files.writeString(workspace.resolve(".env"), "TOKEN=do-not-display");
        collector.after(snapshot, secret, ToolResult.success("Created", Map.of("changed", true,
                "versionAfter", hash("TOKEN=do-not-display"))));
        var escaped = call("create_file", "../outside.txt", "x");
        assertFalse(collector.before(escaped).available());
        var review = collector.finish("run-5", Map.of());
        assertTrue(review.changedFiles().get(0).diff().contains("Diff hidden"));
        assertFalse(review.changedFiles().get(0).diff().contains("do-not-display"));
    }

    @Test void largeDiffIsBoundedAndExplicitlyMarked() throws Exception {
        Path file = workspace.resolve("Large.txt");
        Files.writeString(file, "old");
        TaskDiffCollector collector = new TaskDiffCollector(workspace);
        var call = call("apply_patch", "Large.txt", "big");
        var before = collector.before(call);
        String large = "x".repeat(TaskDiffCollector.MAX_FILE_DIFF_CHARS * 2);
        Files.writeString(file, large);
        collector.after(before, call, changed("Large.txt", "old", large));
        var review = collector.finish("run-6", Map.of());
        assertTrue(review.changedFiles().get(0).diff().length() <= TaskDiffCollector.MAX_FILE_DIFF_CHARS);
        assertTrue(review.changedFiles().get(0).diff().contains("DIFF TRUNCATED"));
        assertFalse(review.diffComplete());
    }

    @Test void preservesUnicodeAndReportsLineEndingOnlyChanges() throws Exception {
        Path file = workspace.resolve("Unicode.txt");
        Files.writeString(file, "你好\n", StandardCharsets.UTF_8);
        TaskDiffCollector collector = new TaskDiffCollector(workspace);
        var call = call("apply_patch", "Unicode.txt", "eol");
        var before = collector.before(call);
        String crlf = "你好\r\n";
        Files.writeString(file, crlf, StandardCharsets.UTF_8);
        collector.after(before, call, changed("Unicode.txt", "你好\n", crlf));
        var fileChange = collector.finish("run-7", Map.of()).changedFiles().get(0);
        assertTrue(fileChange.diff().contains("Line ending style changed"));
        assertTrue(fileChange.diff().contains("你好"));
    }

    @Test void reportsMultipleFilesAndIncludesOnlyToolTargetedPaths() throws Exception {
        TaskDiffCollector collector = new TaskDiffCollector(workspace);
        for (String name : java.util.List.of("A.txt", "B.txt")) {
            var call = call("create_file", name, name);
            var before = collector.before(call);
            Files.writeString(workspace.resolve(name), name);
            collector.after(before, call, ToolResult.success("Created", Map.of("changed", true,
                    "versionAfter", hash(name))));
        }
        var review = collector.finish("run-8", Map.of());
        assertEquals(java.util.List.of("A.txt", "B.txt"),
                review.changedFiles().stream().map(TaskChangeReview.FileChange::path).toList());
    }

    private static ToolCall call(String name, String path, String id) {
        return new ToolCall(id, name, "{\"path\":\"" + path + "\"}");
    }
    private static ToolResult changed(String path, String before, String after) {
        return ToolResult.success("changed", Map.of("path", path, "changed", true,
                "versionBefore", hash(before), "versionAfter", hash(after)));
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new AssertionError(exception); }
    }
}
