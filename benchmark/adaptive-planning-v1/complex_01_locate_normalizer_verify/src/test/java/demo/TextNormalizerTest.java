package demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextNormalizerTest {
    @Test
    void trimsAndLowercases() {
        assertEquals("hello", new TextNormalizer().normalize("  HELLO  "));
    }
}
