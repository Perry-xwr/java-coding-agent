package bench;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
class EvenNumbersTest { @Test void fourIsEven() { assertFalse(new EvenNumbers().isEven(4)); } }
