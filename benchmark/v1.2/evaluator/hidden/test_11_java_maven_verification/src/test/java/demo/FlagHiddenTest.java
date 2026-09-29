package demo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
class FlagHiddenTest { @Test void flagIsOn() { assertTrue(new Flag().on()); } }
