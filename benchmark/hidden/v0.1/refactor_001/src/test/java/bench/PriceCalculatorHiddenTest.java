package bench; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class PriceCalculatorHiddenTest { @Test void behaviorIsPreserved() { PriceCalculator p=new PriceCalculator(); assertEquals(120,p.retail(100),0.001); assertEquals(105,p.wholesale(100),0.001); } }
