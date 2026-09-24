package bench; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class CalculatorHiddenTest { @Test void addsPositiveAndNegativeValues() { Calculator c=new Calculator(); assertEquals(5,c.add(2,3)); assertEquals(-1,c.add(2,-3)); } }
