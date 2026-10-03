package demo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CalculatorTest {
    @Test void addsNormally() { assertEquals(5, new Calculator().add(2, 3)); }
    @Test void reportsOverflow() { assertThrows(ArithmeticException.class, () -> new Calculator().add(Integer.MAX_VALUE, 1)); }
}
