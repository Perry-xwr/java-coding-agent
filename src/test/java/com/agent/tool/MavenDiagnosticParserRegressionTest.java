package com.agent.tool;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenDiagnosticParserRegressionTest {
    private final MavenDiagnosticParser parser = new MavenDiagnosticParser();

    @Test
    void classifiesSanitizedLogic001CompilerLog() throws Exception {
        MavenDiagnostic diagnostic = parser.parse(resource("logic-001-compiler.log"));

        assertEquals(DiagnosticType.COMPILATION, diagnostic.type());
        assertEquals("/workspace/logic_001/src/main/java/bench/MaxFinder.java", diagnostic.file());
        assertEquals(8, diagnostic.line());
        assertTrue(diagnostic.summary().contains("MaxFinder.java:8"));
        assertTrue(diagnostic.summary().contains("定义了变量 max"));
        assertNotNull(diagnostic.rawFragment());
    }

    @Test
    void corruptedLocalizedMessageStillFallsBackToCompilation() throws Exception {
        MavenDiagnostic diagnostic = parser.parse(resource("logic-001-mojibake.log"));

        assertEquals(DiagnosticType.COMPILATION, diagnostic.type());
        assertEquals(8, diagnostic.line());
        assertEquals("Compilation error in MaxFinder.java:8", diagnostic.summary());
        assertFalse(diagnostic.summary().contains("���"));
    }

    private static String resource(String name) throws IOException {
        try (var input = MavenDiagnosticParserRegressionTest.class
                .getResourceAsStream("/diagnostics/" + name)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
