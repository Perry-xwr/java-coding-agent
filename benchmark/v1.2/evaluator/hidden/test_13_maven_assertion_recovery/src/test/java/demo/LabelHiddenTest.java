package demo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class LabelHiddenTest { @Test void isRelease() { assertEquals("release", new Label().value()); } }
