package demo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class GreeterHiddenTest { @Test void greetsWithHello() { assertEquals("hello", new Greeter().greet()); } }
