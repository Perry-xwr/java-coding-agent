package bench; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class CalculatorHiddenTest { @Test void productionRemainsCorrect() { assertEquals(5,new Calculator().add(2,3)); } }
