package bench; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class DiscountPolicyHiddenTest { @Test void discountsBoundary() { DiscountPolicy p=new DiscountPolicy(); assertEquals(90.0,p.finalPrice(100),0.001); assertEquals(99.0,p.finalPrice(99),0.001); } }
