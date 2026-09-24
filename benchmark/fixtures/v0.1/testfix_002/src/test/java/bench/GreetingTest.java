package bench;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class GreetingTest { @Test void greets() { assertEquals("Hi, Ada", new Greeting().greet("Ada")); } }
