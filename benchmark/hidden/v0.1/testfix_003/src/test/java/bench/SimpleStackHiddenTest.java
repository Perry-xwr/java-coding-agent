package bench; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class SimpleStackHiddenTest { @Test void productionRemainsCorrect() { SimpleStack s=new SimpleStack(); s.push("a"); s.push("b"); assertEquals(2,s.size()); } }
