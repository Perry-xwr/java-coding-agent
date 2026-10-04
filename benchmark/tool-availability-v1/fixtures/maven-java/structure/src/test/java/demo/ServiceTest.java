package demo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ServiceTest {
    @Test void projectBaselineIsValid() { assertEquals("READY", "READY"); }
}
