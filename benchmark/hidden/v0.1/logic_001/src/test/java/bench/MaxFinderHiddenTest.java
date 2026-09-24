package bench; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class MaxFinderHiddenTest { @Test void findsNegativeMaximum() { assertEquals(-2,new MaxFinder().max(new int[]{-8,-2,-5})); } @Test void rejectsEmpty() { assertThrows(IllegalArgumentException.class,()->new MaxFinder().max(new int[]{})); } }
