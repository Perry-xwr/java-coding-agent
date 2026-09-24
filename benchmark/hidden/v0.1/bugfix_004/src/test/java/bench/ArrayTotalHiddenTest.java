package bench; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class ArrayTotalHiddenTest { @Test void sumsAndSupportsEmptyArray() { ArrayTotal t=new ArrayTotal(); assertEquals(6,t.sum(new int[]{1,2,3})); assertEquals(0,t.sum(new int[]{})); } }
