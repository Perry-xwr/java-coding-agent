package bench; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class GreetingHiddenTest { @Test void productionRemainsCorrect() { assertEquals("Hello, Ada",new Greeting().greet("Ada")); } }
