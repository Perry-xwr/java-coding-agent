package com.agent.tool.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultProcessRunnerEncodingTest {
    @TempDir
    Path temporary;

    @Test
    void capturesUtf8JavaSubprocessOutputWithoutMojibake() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? "java.exe" : "java");
        ProcessExecutionResult result = new DefaultProcessRunner().run(
                List.of(
                        java.toString(),
                        "-cp", System.getProperty("java.class.path"),
                        Utf8Emitter.class.getName()
                ),
                temporary,
                Duration.ofSeconds(15),
                8_192
        );

        assertTrue(result.output().contains("重复变量 max"));
        assertFalse(result.output().contains("���"));
    }

    public static final class Utf8Emitter {
        public static void main(String[] args) {
            System.out.println("编译错误：重复变量 max");
        }
    }
}
