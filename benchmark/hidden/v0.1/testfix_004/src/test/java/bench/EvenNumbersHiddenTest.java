package bench; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class EvenNumbersHiddenTest { @Test void productionRemainsCorrect() { assertTrue(new EvenNumbers().isEven(4)); assertFalse(new EvenNumbers().isEven(3)); } }
