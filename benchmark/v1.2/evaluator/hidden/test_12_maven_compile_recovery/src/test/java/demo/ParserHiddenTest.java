package demo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class ParserHiddenTest { @Test void parsesOne() { assertEquals(1, new Parser().parse()); } }
