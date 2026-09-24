package bench;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class SimpleStackTest { @Test void pushChangesSize() { SimpleStack stack = new SimpleStack(); stack.push("x"); assertEquals(0, stack.size()); } }
