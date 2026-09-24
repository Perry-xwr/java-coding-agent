package bench; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class NameFormatterHiddenTest { @Test void behaviorIsPreserved() { NameFormatter f=new NameFormatter(); assertTrue(f.same(" Ada ","ada")); assertEquals("bob",f.key(" Bob ")); } }
