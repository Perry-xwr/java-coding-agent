package demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatterTest {
    @Test
    void followsFormatRule() {
        assertEquals("mixed", new Formatter().format("MIXED"));
    }
}
