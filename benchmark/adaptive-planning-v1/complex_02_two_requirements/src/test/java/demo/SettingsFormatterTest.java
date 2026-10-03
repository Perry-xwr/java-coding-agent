package demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingsFormatterTest {
    @Test
    void strictModeIsDefaultAndFormattingIsLowercase() {
        assertEquals("strict", new Settings().mode());
        assertEquals("mixed", new Formatter().format("MIXED"));
    }
}
