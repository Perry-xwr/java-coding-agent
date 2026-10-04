package com.agent.environment.verification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VerificationDiagnosticParserTest {
    @Test void parsesPythonLineAndColumn() {
        assertEquals(new DiagnosticLocation(7, 4), VerificationDiagnosticParser.parse(
                "python-py-compile", "IndentationError: unexpected indent (x.py, line 7, column 4)"));
    }
    @Test void parsesNodeLineAndColumn() {
        assertEquals(new DiagnosticLocation(12, 9), VerificationDiagnosticParser.parse(
                "javascript-node-check", "file.js:12\n    at check (file.js:12:9)"));
    }
    @Test void parsesJavacLine() {
        assertEquals(new DiagnosticLocation(27, 0), VerificationDiagnosticParser.parse(
                "java-javac", "Foo.java:27: error: ';' expected"));
    }
    @Test void parsesGccLineAndColumnAndFallsBackSafely() {
        assertEquals(new DiagnosticLocation(3, 8), VerificationDiagnosticParser.parse(
                "cpp-gxx-syntax", "src/a.cpp:3:8: error: expected ';'"));
        assertEquals(new DiagnosticLocation(27, 0), VerificationDiagnosticParser.parse(
                "java-javac", "D:\\work\\Foo.java:27: error: ';' expected"));
        assertFalse(VerificationDiagnosticParser.parse("java-javac", "compiler failed").known());
    }
}
