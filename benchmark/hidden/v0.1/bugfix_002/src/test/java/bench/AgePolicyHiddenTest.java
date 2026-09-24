package bench; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class AgePolicyHiddenTest { @Test void acceptsBoundary() { AgePolicy p=new AgePolicy(); assertTrue(p.isAdult(18)); assertFalse(p.isAdult(17)); } }
